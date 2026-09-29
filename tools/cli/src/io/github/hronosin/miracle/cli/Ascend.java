package io.github.hronosin.miracle.cli;

import io.github.hronosin.miracle.MiniToml;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.jar.JarFile;

/**
 * {@code miracle ascend modrinth|github} (alias {@code publish}): bakes the mod and publishes the
 * jar as a new version. The game versions it claims are exactly the ones the bake checked or
 * baked for, never more.
 *
 * <p>Settings, in {@code miracle.project.toml}:
 * <pre>
 * modrinth = "my-mod"                 # the Modrinth project (slug or id); create it on the website first
 * modrinth_loaders = ["miracle"]      # loader tags for the version
 * modrinth_requires = ["xyz"]         # Modrinth projects this one requires (slugs or ids)
 * github = "you/my-mod"               # the GitHub repository for releases
 * </pre>
 * Tokens come from the environment only: {@code MODRINTH_TOKEN}, and {@code GITHUB_TOKEN} (or
 * {@code GH_TOKEN}, or whatever {@code gh auth token} says). They are sent to their own site and
 * nowhere else, and never printed.
 */
final class Ascend {

    static final String MODRINTH = "https://api.modrinth.com/v2";
    static final String GITHUB = "https://api.github.com";
    private static final String AGENT = "MiracleToolChain/" + Miracle.VERSION + " (github.com/Hronosin/MiracleLoader)";

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    /** What goes up, wherever it goes. */
    record Offering(Project project, Path jar, List<String> gameVersions, String notes, String type) {
        String title() {
            return project.name() + " " + project.version();
        }
    }

    private Ascend() {
    }

    static int run(Path dir, List<String> args) throws IOException, InterruptedException {
        List<String> rest = new ArrayList<>(args);
        boolean dry = rest.remove("--dry-run");
        boolean noBuild = rest.remove("--no-build");
        boolean draft = rest.remove("--draft");
        String notesFile = option(rest, "--notes");
        String message = option(rest, "-m");
        String type = option(rest, "--type");
        String tag = option(rest, "--tag");
        String repo = option(rest, "--repo");
        String projectId = option(rest, "--project");
        if (rest.isEmpty() || !List.of("modrinth", "github").contains(rest.getFirst())) {
            throw new Miracle.Heresy("ascend where? miracle ascend modrinth, or miracle ascend github");
        }
        String where = rest.removeFirst();
        if (!rest.isEmpty()) {
            throw new Miracle.Heresy("ascend doesn't know " + rest + ". See miracle help.");
        }
        if (type != null && !List.of("release", "beta", "alpha").contains(type)) {
            throw new Miracle.Heresy("--type " + type + ": release, beta or alpha");
        }
        if (notesFile != null && message != null) {
            throw new Miracle.Heresy("--notes or -m, not both");
        }

        Project p = Project.find(dir);
        Map<String, Object> settings = settings(p);
        Path jar = noBuild ? p.jar() : Builder.build(p);
        if (!Files.isRegularFile(jar)) {
            throw new Miracle.Heresy("Nothing to offer: " + p.dir().relativize(jar) + " doesn't exist. Bake first"
                    + " (or drop --no-build).");
        }
        List<String> versions = gameVersions(jar, p.minecraft());
        String notes = notesFile != null ? Files.readString(p.dir().resolve(notesFile)).strip()
                : message != null ? message : p.name() + " " + p.version() + ".";
        Offering o = new Offering(p, jar, versions, notes, type != null ? type : typeOf(p.version()));

        System.out.println("Ascending " + o.title() + " (" + o.type() + ") to " + (where.equals("modrinth") ? "Modrinth" : "GitHub"));
        System.out.println("  jar:        " + p.dir().relativize(jar) + " (" + Rituals.human(Files.size(jar)) + ")");
        System.out.println("  Minecraft:  " + String.join(", ", versions) + "  (what the bake checked or baked, nothing more)");
        return where.equals("modrinth")
                ? modrinth(o, settings, projectId, dry)
                : github(o, settings, repo, tag, draft, dry);
    }

    // --- Modrinth -------------------------------------------------------------------------------

    private static int modrinth(Offering o, Map<String, Object> settings, String projectArg, boolean dry)
            throws IOException, InterruptedException {
        String project = projectArg != null ? projectArg : settings.get("modrinth") instanceof String s ? s : null;
        if (project == null || project.isBlank()) {
            throw new Miracle.Heresy("Which Modrinth project? Create it on modrinth.com, then put modrinth = \"its-slug\""
                    + " in miracle.project.toml (or pass --project its-slug).");
        }
        List<String> loaders = strings(settings.get("modrinth_loaders"), List.of("miracle"));
        List<String> requires = strings(settings.get("modrinth_requires"), List.of());
        String api = env("MIRACLE_MODRINTH_API", MODRINTH);

        List<Object> deps = new ArrayList<>();
        for (String r : requires) {
            deps.add(map("project_id", r, "dependency_type", "required"));
        }
        String file = o.jar().getFileName().toString();
        Map<String, Object> data = map(
                "name", o.title(),
                "version_number", o.project().version(),
                "changelog", o.notes(),
                "dependencies", deps,
                "game_versions", o.gameVersions(),
                "version_type", o.type(),
                "loaders", loaders,
                "featured", true,
                "status", "listed",
                "project_id", project,
                "file_parts", List.of("file"),
                "primary_file", "file");
        System.out.println("  project:    " + project + ", loaders " + loaders
                + (requires.isEmpty() ? "" : ", requires " + requires));
        if (dry) {
            System.out.println("  --dry-run: this is what would go up, with " + file + " attached:");
            System.out.println(json(data, "  "));
            return 0;
        }
        String token = System.getenv("MODRINTH_TOKEN");
        if (token == null || token.isBlank()) {
            throw new Miracle.Heresy("MODRINTH_TOKEN isn't set. Make a personal access token with the \"Create versions\""
                    + " scope at modrinth.com/settings/pats, then: MODRINTH_TOKEN=... miracle ascend modrinth");
        }

        // Modrinth only takes loaders it knows. Ask first, so a refusal comes with a reason.
        Set<String> known = new LinkedHashSet<>();
        for (Object l : Json.arr(Json.parse(get(api + "/tag/loader", Map.of())))) {
            known.add(Json.str(l, "name"));
        }
        List<String> unknown = loaders.stream().filter(l -> !known.contains(l)).toList();
        if (!unknown.isEmpty()) {
            throw new Miracle.Heresy("Modrinth doesn't know the loader" + (unknown.size() > 1 ? "s " : " ") + unknown
                    + " (it knows " + known.size() + ": " + String.join(", ", known) + ")."
                    + (unknown.contains("miracle")
                    ? "\n  MiracleLoader isn't on Modrinth's list yet, and a version must name a loader from it."
                      + "\n  Until it is, publish with: miracle ascend github"
                      + "\n  (If you know better, set modrinth_loaders = [...] in miracle.project.toml.)"
                    : ""));
        }
        Set<String> gameVersions = new LinkedHashSet<>();
        for (Object v : Json.arr(Json.parse(get(api + "/tag/game_version", Map.of())))) {
            gameVersions.add(Json.str(v, "version"));
        }
        List<String> claimed = o.gameVersions().stream().filter(gameVersions::contains).toList();
        List<String> dropped = o.gameVersions().stream().filter(v -> !gameVersions.contains(v)).toList();
        if (!dropped.isEmpty()) {
            System.out.println("  note: Modrinth doesn't list " + dropped + " yet; left out");
            data.put("game_versions", claimed);
        }
        if (claimed.isEmpty()) {
            throw new Miracle.Heresy("None of " + o.gameVersions() + " is a Minecraft version Modrinth knows.");
        }

        String boundary = "miracle-" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        part(body, boundary, "data", null, "application/json", json(data, "").getBytes(StandardCharsets.UTF_8));
        part(body, boundary, "file", file, "application/java-archive", Files.readAllBytes(o.jar()));
        body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpResponse<String> r = send(HttpRequest.newBuilder(URI.create(api + "/version"))
                .header("Authorization", token)
                .header("User-Agent", AGENT)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .timeout(Duration.ofMinutes(5))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build());
        if (r.statusCode() / 100 != 2) {
            throw new Miracle.Heresy("Modrinth refused (HTTP " + r.statusCode() + "): " + reason(r.body()));
        }
        String id = Json.str(Json.parse(r.body()), "id");
        System.out.println("It has ascended: https://modrinth.com/mod/" + project + "/version/" + id);
        return 0;
    }

    // --- GitHub ---------------------------------------------------------------------------------

    private static int github(Offering o, Map<String, Object> settings, String repoArg, String tagArg, boolean draft,
                              boolean dry) throws IOException, InterruptedException {
        String repo = repoArg != null ? repoArg : settings.get("github") instanceof String s ? s : null;
        if (repo == null || !repo.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) {
            throw new Miracle.Heresy("Which repository? Put github = \"you/your-mod\" in miracle.project.toml"
                    + " (or pass --repo you/your-mod).");
        }
        String tag = tagArg != null ? tagArg : "v" + o.project().version();
        String api = env("MIRACLE_GITHUB_API", GITHUB);
        String body = o.notes() + "\n\n---\nChecked against Minecraft " + String.join(", ", o.gameVersions())
                + " by `miracle bake`. Needs MiracleLoader" + (o.project().usesToolchain() ? " and MiracleToolChain" : "")
                + ".\n\n_Ascended with MiracleToolChain " + Miracle.VERSION + "._";
        Map<String, Object> release = map(
                "tag_name", tag,
                "name", o.title(),
                "body", body,
                "draft", draft,
                "prerelease", !o.type().equals("release"));
        System.out.println("  repository: " + repo + ", tag " + tag + (draft ? " (draft)" : ""));
        if (dry) {
            System.out.println("  --dry-run: this is what would go up, with " + o.jar().getFileName() + " attached:");
            System.out.println(json(release, "  "));
            return 0;
        }
        String token = githubToken();
        Map<String, String> auth = Map.of("Authorization", "Bearer " + token,
                "Accept", "application/vnd.github+json", "X-GitHub-Api-Version", "2022-11-28");

        HttpResponse<String> r = send(with(HttpRequest.newBuilder(URI.create(api + "/repos/" + repo + "/releases")), auth)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json(release, ""))).build());
        if (r.statusCode() != 201) {
            String why = reason(r.body());
            if (r.statusCode() == 422 && why.contains("already_exists")) {
                why = "a release for " + tag + " already exists. Bump the version, or pass --tag.";
            }
            throw new Miracle.Heresy("GitHub refused the release (HTTP " + r.statusCode() + "): " + why);
        }
        Object created = Json.parse(r.body());
        String upload = Json.str(created, "upload_url");
        int brace = upload.indexOf('{');
        if (brace >= 0) {
            upload = upload.substring(0, brace);
        }
        String name = o.jar().getFileName().toString();
        HttpResponse<String> a = send(with(HttpRequest.newBuilder(URI.create(upload + "?name="
                        + URLEncoder.encode(name, StandardCharsets.UTF_8))), auth)
                .header("Content-Type", "application/java-archive")
                .timeout(Duration.ofMinutes(5))
                .POST(HttpRequest.BodyPublishers.ofFile(o.jar())).build());
        if (a.statusCode() != 201) {
            throw new Miracle.Heresy("The release exists, but GitHub refused the jar (HTTP " + a.statusCode() + "): "
                    + reason(a.body()) + ". Attach it by hand: " + Json.str(created, "html_url"));
        }
        System.out.println("It has ascended: " + Json.str(created, "html_url"));
        return 0;
    }

    private static String githubToken() throws IOException, InterruptedException {
        for (String var : List.of("GITHUB_TOKEN", "GH_TOKEN")) {
            String t = System.getenv(var);
            if (t != null && !t.isBlank()) {
                return t.strip();
            }
        }
        try {
            Process gh = new ProcessBuilder("gh", "auth", "token").redirectErrorStream(false).start();
            String out;
            try (InputStream in = gh.getInputStream()) {
                out = new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
            }
            if (gh.waitFor() == 0 && !out.isEmpty()) {
                return out;
            }
        } catch (IOException e) {
            // no gh: fall through
        }
        throw new Miracle.Heresy("No GitHub token. Set GITHUB_TOKEN (a token with Contents: write on the repository),"
                + " or log in with the GitHub CLI: gh auth login");
    }

    // --- what the bake vouches for --------------------------------------------------------------

    /**
     * The versions the jar was compiled against, checked against or baked for, newest first. Read
     * from the jar's {@code bake.toml}: a jar that was never baked vouches only for {@code minecraft}.
     */
    static List<String> gameVersions(Path jar, String primary) throws IOException {
        Set<String> out = new LinkedHashSet<>();
        out.add(primary);
        try (JarFile jf = new JarFile(jar.toFile())) {
            var e = jf.getJarEntry("META-INF/miracle/bake.toml");
            if (e != null) {
                try (InputStream in = jf.getInputStream(e)) {
                    Map<String, Object> bake = MiniToml.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                    out.addAll(strings(bake.get("checked"), List.of()));
                    out.addAll(strings(bake.get("baked"), List.of()));
                } catch (MiniToml.ParseException ex) {
                    throw new Miracle.Heresy(jar.getFileName() + " has a broken bake.toml: " + ex.getMessage());
                }
            }
        }
        List<String> sorted = new ArrayList<>(out);
        sorted.sort(Comparator.comparing((String v) -> v, Targets::compare).reversed());
        return sorted;
    }

    /** 1.0.0-beta.2 is a beta, 0.3-alpha an alpha, 2.0-rc1 a beta, the rest releases. */
    static String typeOf(String version) {
        String v = version.toLowerCase(Locale.ROOT);
        if (v.contains("alpha") || v.contains("snapshot")) {
            return "alpha";
        }
        if (v.contains("beta") || v.contains("-rc") || v.contains("pre")) {
            return "beta";
        }
        return "release";
    }

    // --- plumbing -------------------------------------------------------------------------------

    private static Map<String, Object> settings(Project p) throws IOException {
        try {
            return MiniToml.parse(Files.readString(p.dir().resolve(Project.PROJECT_FILE)));
        } catch (MiniToml.ParseException e) {
            throw new Miracle.Heresy(Project.PROJECT_FILE + ": " + e.getMessage());
        }
    }

    private static String get(String url, Map<String, String> headers) throws IOException, InterruptedException {
        HttpResponse<String> r = send(with(HttpRequest.newBuilder(URI.create(url)), headers).GET().build());
        if (r.statusCode() != 200) {
            throw new IOException("HTTP " + r.statusCode() + " for " + url);
        }
        return r.body();
    }

    private static HttpRequest.Builder with(HttpRequest.Builder b, Map<String, String> headers) {
        headers.forEach(b::header);
        return b.header("User-Agent", AGENT).timeout(Duration.ofMinutes(1));
    }

    private static HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static void part(ByteArrayOutputStream out, String boundary, String name, String filename, String type,
                             byte[] content) throws IOException {
        String head = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\""
                + (filename == null ? "" : "; filename=\"" + filename.replace("\"", "") + "\"")
                + "\r\nContent-Type: " + type + "\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(content);
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    /** The error message in an API's JSON answer, or the answer itself. */
    private static String reason(String body) {
        try {
            Object j = Json.parse(body);
            for (String key : List.of("description", "message", "error")) {
                if (Json.get(j, key) instanceof String s) {
                    Object errors = Json.get(j, "errors");
                    return s + (errors == null ? "" : " " + errors);
                }
            }
        } catch (RuntimeException ignored) {
            // not JSON
        }
        return body.length() > 400 ? body.substring(0, 400) + "..." : body;
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v.replaceAll("/+$", "");
    }

    private static List<String> strings(Object value, List<String> fallback) {
        return value instanceof List<?> l ? l.stream().map(Object::toString).toList() : fallback;
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    /**
     * JSON of maps, lists, strings, numbers and booleans. A non-empty {@code indent} pretty-prints,
     * every line starting with it.
     */
    static String json(Object v, String indent) {
        StringBuilder sb = new StringBuilder();
        write(sb, v, !indent.isEmpty(), "");
        return indent.isEmpty() ? sb.toString() : indent + sb.toString().replace("\n", "\n" + indent);
    }

    private static void write(StringBuilder sb, Object v, boolean pretty, String at) {
        String inner = at + "  ";
        switch (v) {
            case null -> sb.append("null");
            case Map<?, ?> m -> {
                sb.append('{');
                int i = 0;
                for (var e : m.entrySet()) {
                    sb.append(i++ == 0 ? "" : ",");
                    if (pretty) {
                        sb.append('\n').append(inner);
                    }
                    quote(sb, e.getKey().toString());
                    sb.append(pretty ? ": " : ":");
                    write(sb, e.getValue(), pretty, inner);
                }
                if (pretty && !m.isEmpty()) {
                    sb.append('\n').append(at);
                }
                sb.append('}');
            }
            case List<?> l -> {
                sb.append('[');
                for (int i = 0; i < l.size(); i++) {
                    sb.append(i == 0 ? "" : pretty ? ", " : ",");
                    write(sb, l.get(i), false, inner); // lists stay on one line
                }
                sb.append(']');
            }
            case Boolean b -> sb.append(b);
            case Number n -> sb.append(n);
            default -> quote(sb, v.toString());
        }
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    private static String option(List<String> args, String name) {
        int i = args.indexOf(name);
        if (i < 0) {
            return null;
        }
        if (i + 1 >= args.size()) {
            throw new Miracle.Heresy(name + " needs a value");
        }
        String v = args.get(i + 1);
        args.remove(i + 1);
        args.remove(i);
        return v;
    }
}
