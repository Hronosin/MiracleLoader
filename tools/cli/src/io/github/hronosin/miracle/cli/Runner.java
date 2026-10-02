package io.github.hronosin.miracle.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * {@code miracle pray client|server}: bake the mod, then run Minecraft with MiracleLoader and the
 * mod, in {@code run/<side>-<version>/}. The client plays offline (singleplayer, no Microsoft
 * account needed); the server asks before accepting Mojang's EULA.
 */
final class Runner {

    record Options(String side, String version, boolean build, boolean eula, String username, List<String> extra) {
    }

    private Runner() {
    }

    static int pray(Project p, Options o) throws IOException, InterruptedException {
        if (!o.side().equals("client") && !o.side().equals("server")) {
            throw new Miracle.Heresy("pray what? client or server, not '" + o.side() + "'");
        }
        Path mod = o.build() ? Builder.build(p) : p.jar();
        if (!Files.isRegularFile(mod)) {
            throw new Miracle.Heresy("No baked mod at " + mod + ". Run without --no-build.");
        }
        String version = o.version() != null ? o.version() : p.minecraft();
        if (!version.equals(p.minecraft()) && !Targets.expand(p.targets()).contains(version)) {
            System.out.println("Note: " + version + " isn't in this project's targets " + p.targets()
                    + "; the loader will say what it thinks of that.");
        }
        Mojang.Version v = Mojang.version(version);
        Path run = p.dir().resolve("run").resolve(o.side() + "-" + version);
        Path mods = run.resolve("mods");
        Files.createDirectories(mods);
        try (Stream<Path> old = Files.list(mods)) {
            // Only this mod's own earlier builds: <id>-<version>.jar, the version starting with a digit.
            // A mod called holy mustn't take holy-hops-1.0.jar with it.
            java.util.regex.Pattern ours = java.util.regex.Pattern.compile(
                    java.util.regex.Pattern.quote(p.id() + "-") + "\\d[^/]*\\.jar");
            for (Path f : old.filter(f -> ours.matcher(f.getFileName().toString()).matches()).toList()) {
                Files.delete(f);
            }
        }
        Files.copy(mod, mods.resolve(mod.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        Path library = mods.resolve("miracle-toolchain.jar");
        Path horizon = mods.resolve("event-horizon.jar");
        if (p.usesHorizon()) {
            Files.copy(Miracle.horizonJar(), horizon, StandardCopyOption.REPLACE_EXISTING);
        } else {
            Files.deleteIfExists(horizon);
        }
        if (p.usesToolchain()) {
            Files.copy(Miracle.toolchainJar(), library, StandardCopyOption.REPLACE_EXISTING);
        } else {
            Files.deleteIfExists(library);
        }
        for (Path other : p.against()) {
            // the mods among them come along, so entanglements can be tried; plain libraries don't
            try (var jf = new java.util.jar.JarFile(other.toFile())) {
                if (jf.getJarEntry("miracle.mod.toml") != null) {
                    Files.copy(other, mods.resolve(other.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(Miracle.javaExecutable());
        return switch (o.side()) {
            case "server" -> server(v, run, cmd, o);
            case "client" -> client(v, run, cmd, o);
            default -> throw new Miracle.Heresy("pray what? client or server");
        };
    }

    private static int server(Mojang.Version v, Path run, List<String> cmd, Options o) throws IOException, InterruptedException {
        Path eula = run.resolve("eula.txt");
        if (o.eula()) {
            Files.writeString(eula, "# Accepted with miracle pray --eula: https://aka.ms/MinecraftEULA\neula=true\n");
        }
        if (!Files.isRegularFile(eula) || !Files.readString(eula).contains("eula=true")) {
            System.out.println("""
                    Mojang wants you to accept the Minecraft EULA before a server may run.
                    Unlike our EULA, theirs is real: https://aka.ms/MinecraftEULA
                    If you agree, pray again with --eula.""");
            return 1;
        }
        Path props = run.resolve("server.properties");
        if (!Files.isRegularFile(props)) {
            // A development server: reachable from this machine only, and open to the offline
            // client that 'pray client' starts. Edit freely; it's only written once.
            Files.writeString(props, """
                    # Written once by miracle pray. A dev server: local only, offline logins allowed.
                    server-ip=127.0.0.1
                    online-mode=false
                    spawn-protection=0
                    motd=A MiracleToolChain server. Pray responsibly.
                    """);
            System.out.println("Wrote a dev server.properties: localhost only, offline logins allowed.");
        }
        Mojang.Server s = Mojang.server(v);
        List<Path> cp = new ArrayList<>();
        cp.add(Miracle.loaderJar());
        cp.add(s.jar());
        cp.addAll(s.libraries());
        cmd.addAll(List.of("-Xmx2G", "--enable-native-access=ALL-UNNAMED", "--sun-misc-unsafe-memory-access=allow",
                "-Dmiracle.target=net.minecraft.server.Main", "-cp", classpath(cp),
                "io.github.hronosin.miracle.MiracleMain", "nogui"));
        cmd.addAll(o.extra());
        return launch(cmd, run, v);
    }

    private static int client(Mojang.Version v, Path run, List<String> cmd, Options o) throws IOException, InterruptedException {
        Path client = Mojang.clientJar(v);
        List<Path> libs = Mojang.libraries(v);
        String assetIndex = Mojang.assets(v);
        Path natives = v.dir().resolve("natives");
        Files.createDirectories(natives);

        List<Path> cp = new ArrayList<>();
        cp.add(Miracle.loaderJar());
        cp.addAll(libs);
        cp.add(client);

        String name = o.username();
        Map<String, String> vars = Map.ofEntries(
                Map.entry("natives_directory", natives.toString()),
                Map.entry("launcher_name", "MiracleToolChain"),
                Map.entry("launcher_version", Miracle.VERSION),
                Map.entry("classpath", classpath(cp)),
                Map.entry("auth_player_name", name),
                Map.entry("version_name", v.id()),
                Map.entry("game_directory", run.toString()),
                Map.entry("assets_root", Mojang.mc().resolve("assets").toString()),
                Map.entry("assets_index_name", assetIndex),
                // Offline: the same UUID vanilla derives for an offline player of this name.
                Map.entry("auth_uuid", UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8))
                        .toString().replace("-", "")),
                Map.entry("auth_access_token", "0"),
                Map.entry("clientid", ""),
                Map.entry("auth_xuid", ""),
                Map.entry("user_type", "legacy"),
                Map.entry("version_type", "MiracleToolChain"));

        cmd.addAll(List.of("-Xms1G", "-Xmx4G"));
        List<String> jvm = arguments(Json.get(v.json(), "arguments", "jvm"), vars);
        // Point the game at the loader: MiracleMain first, the real main class as a property.
        int cpAt = jvm.indexOf("-cp");
        cmd.addAll(jvm);
        if (cpAt < 0) {
            cmd.addAll(List.of("-cp", vars.get("classpath")));
        }
        if (!cmd.contains("--enable-native-access=ALL-UNNAMED")) {
            cmd.add("--enable-native-access=ALL-UNNAMED");
        }
        cmd.add("-Dmiracle.target=" + v.json().get("mainClass"));
        cmd.add("io.github.hronosin.miracle.MiracleMain");
        cmd.addAll(arguments(Json.get(v.json(), "arguments", "game"), vars));
        cmd.addAll(o.extra());
        return launch(cmd, run, v);
    }

    /** Mojang's argument lists: plain strings, plus entries that apply only when their rules allow. */
    private static List<String> arguments(Object list, Map<String, String> vars) {
        List<String> out = new ArrayList<>();
        for (Object a : Json.arr(list)) {
            if (a instanceof String s) {
                out.add(substitute(s, vars));
            } else if (Mojang.rulesAllow(Json.get(a, "rules"))) {
                Object value = Json.get(a, "value");
                if (value instanceof String s) {
                    out.add(substitute(s, vars));
                } else {
                    Json.arr(value).forEach(x -> out.add(substitute(x.toString(), vars)));
                }
            }
        }
        return out;
    }

    private static String substitute(String s, Map<String, String> vars) {
        for (var e : vars.entrySet()) {
            s = s.replace("${" + e.getKey() + "}", e.getValue());
        }
        return s;
    }

    private static int launch(List<String> cmd, Path run, Mojang.Version v) throws IOException, InterruptedException {
        System.out.println("Praying for Minecraft " + v.id() + " in " + run + " ...");
        if (Boolean.getBoolean("miracle.showCommand")) {
            System.out.println(String.join(" ", cmd));
        }
        long started = System.currentTimeMillis();
        // The output passes through untouched, but an autopsy watches it for deaths worth explaining.
        Process proc = new ProcessBuilder(cmd).directory(run.toFile())
                .redirectInput(ProcessBuilder.Redirect.INHERIT).start();
        Autopsy autopsy = new Autopsy();
        Thread out = autopsy.pump(proc.getInputStream(), System.out, "miracle-game-out");
        Thread err = autopsy.pump(proc.getErrorStream(), System.err, "miracle-game-err");
        int code = proc.waitFor();
        out.join(2000);
        err.join(2000);
        // A crashed dedicated server still exits with 0; the fresh crash report gives it away. So
        // does a client that found no graphics: it shows a message and leaves politely.
        Path report = newestCrashReport(run, started);
        Autopsy.Cause cause = autopsy.cause();
        if (code == 0 && report == null && (cause == null || !cause.fatal)) {
            System.out.println("Amen.");
            return 0;
        }
        youDied(code, report, cause);
        return code == 0 ? 1 : code;
    }

    /** The game didn't leave peacefully. Say so the way it deserves, and point at the evidence. */
    private static void youDied(int code, Path report, Autopsy.Cause cause) {
        System.out.println("""

                ==================================================
                                   YOU DIED
                ==================================================""");
        System.out.println(code != 0 ? "The game left with exit code " + code + "." : "The game crashed, then left politely.");
        if (report != null) {
            System.out.println("Crash report: " + report);
        }
        if (cause != null) {
            System.out.println("What killed it: " + cause.meaning);
        } else {
            System.out.println("Read the log above, fix, and pray again. (Light a bonfire first next time: miracle bonfire)");
        }
        String remark = Yukari.says(Yukari.onDeath(cause, code));
        if (remark != null) {
            System.out.println(remark);
        }
    }

    /** A crash report written since {@code since}, or null. */
    private static Path newestCrashReport(Path run, long since) throws IOException {
        Path reports = run.resolve("crash-reports");
        if (!Files.isDirectory(reports)) {
            return null;
        }
        try (Stream<Path> s = Files.list(reports)) {
            return s.filter(f -> f.toString().endsWith(".txt"))
                    .filter(f -> {
                        try {
                            return Files.getLastModifiedTime(f).toMillis() >= since;
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .max(Rituals.newestFirst().reversed()).orElse(null);
        }
    }

    private static String classpath(List<Path> cp) {
        return String.join(java.io.File.pathSeparator, cp.stream().map(Path::toString).toList());
    }
}
