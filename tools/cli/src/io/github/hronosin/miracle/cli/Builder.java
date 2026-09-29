package io.github.hronosin.miracle.cli;

import io.github.hronosin.miracle.bake.Bake;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;

/**
 * {@code miracle bake}: compile, add fallbacks, jar, then check and bake against every target.
 * javac runs in-process through {@link javax.tools}; nothing but the JDK is needed.
 */
final class Builder {

    private Builder() {
    }

    static Path build(Project p) throws IOException {
        long started = System.nanoTime();
        Mojang.Version primary = Mojang.version(p.minecraft());
        if (primary.obfuscated()) {
            throw new Miracle.Heresy("minecraft = \"" + p.minecraft() + "\" is obfuscated. Write and compile against"
                    + " an unobfuscated version (26.1 or newer) and put " + p.minecraft() + " in targets instead:"
                    + " miracle bake translates for it.");
        }
        System.out.println("Baking " + p.name() + " " + p.version() + " against Minecraft " + primary.id());
        Path client = Mojang.clientJar(primary);
        List<Path> libs = Mojang.compileLibraries(primary);

        Path classes = p.buildDir().resolve("classes");
        wipe(classes);
        Files.createDirectories(classes);
        List<Path> base = new ArrayList<>();
        base.add(Miracle.loaderJar());
        if (p.usesToolchain()) {
            base.add(Miracle.toolchainJar());
        }
        base.addAll(libs);

        List<Path> cp = new ArrayList<>(base);
        cp.add(client);
        compile(p.dir().resolve("src"), classes, cp, "main sources");

        copyTree(p.dir().resolve("resources"), classes);
        Files.copy(p.dir().resolve(Project.MOD_FILE), classes.resolve(Project.MOD_FILE));

        // Fallback functions: each against its own version's readable API.
        Path fallbacks = p.dir().resolve("fallback");
        if (Files.isDirectory(fallbacks)) {
            try (Stream<Path> dirs = Files.list(fallbacks)) {
                for (Path d : dirs.filter(Files::isDirectory).sorted().toList()) {
                    String v = d.getFileName().toString();
                    List<Path> fcp = new ArrayList<>(base);
                    fcp.add(classes);
                    fcp.add(api(Mojang.version(v)));
                    compile(d.resolve("src"), classes.resolve("META-INF/miracle/fallback/" + v), fcp, "fallbacks for " + v);
                }
            }
        }

        Path jar = p.jar();
        jar(classes, jar);

        List<String> args = new ArrayList<>(List.of("--native", primary.id() + "=" + client));
        for (String t : p.targets()) {
            if (t.equals(primary.id())) {
                continue;
            }
            Mojang.Version v = Mojang.version(t);
            Path dict = Mojang.dictionary(v);
            if (v.obfuscated()) {
                args.add("--obf");
                args.add(t + "=" + dict.resolve("client.jar") + "," + dict.resolve("mappings.txt"));
            } else {
                args.add("--native");
                args.add(t + "=" + dict.resolve("client.jar"));
            }
        }
        args.add(jar.toString());
        bake(args.toArray(String[]::new));
        System.out.println("Baked: " + p.dir().relativize(jar));
        System.out.println(style((System.nanoTime() - started) / 1_000_000));
        return jar;
    }

    /** How stylish the bake was, by how long it took (Devil May Cry rules; Gradle scores D on a good day). */
    static String style(long millis) {
        String rank;
        if (millis < 2500) {
            rank = "SSS  Smokin' Sexy Style!!  Jackpot!";
        } else if (millis < 4000) {
            rank = "SS   Sick Skills!";
        } else if (millis < 7000) {
            rank = "S    Savage!";
        } else if (millis < 12_000) {
            rank = "A    Apocalyptic!";
        } else if (millis < 20_000) {
            rank = "B    Badass!";
        } else if (millis < 40_000) {
            rank = "C    Crazy!";
        } else {
            rank = "D    Dismal. (first bakes download Minecraft; it gets better)";
        }
        return "Style: " + rank + "  (" + String.format(java.util.Locale.ROOT, "%.1f", millis / 1000.0) + "s)";
    }

    /** A jar with readable names to compile against: the client itself, or an API jar made from the dictionary. */
    static Path api(Mojang.Version v) throws IOException {
        Path dict = Mojang.dictionary(v);
        if (!v.obfuscated()) {
            return dict.resolve("client.jar");
        }
        Path api = dict.resolve("api.jar");
        Path mappings = dict.resolve("mappings.txt");
        if (!Files.isRegularFile(api) || Files.getLastModifiedTime(mappings).compareTo(Files.getLastModifiedTime(api)) > 0) {
            bake(new String[] {"--api", v.id() + "=" + dict.resolve("client.jar") + "," + mappings, api.toString()});
        }
        return api;
    }

    private static void bake(String[] args) throws IOException {
        try {
            Bake.main(args);
        } catch (IOException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    private static void compile(Path src, Path out, List<Path> cp, String what) throws IOException {
        List<String> files;
        try (Stream<Path> s = Files.walk(src)) {
            files = s.filter(f -> f.toString().endsWith(".java")).map(Path::toString).sorted().toList();
        } catch (java.nio.file.NoSuchFileException e) {
            throw new Miracle.Heresy("No sources in " + src);
        }
        if (files.isEmpty()) {
            throw new Miracle.Heresy("No .java files in " + src);
        }
        Files.createDirectories(out);
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        if (javac == null) {
            throw new Miracle.Heresy("This Java has no compiler. Install a JDK 25, not just a JRE"
                    + " (Fedora: sudo dnf install java-25-openjdk-devel).");
        }
        List<String> args = new ArrayList<>(List.of("--release", "25", "-encoding", "UTF-8",
                "-Xlint:all,-serial,-path,-classfile,-processing", "-proc:none", "-d", out.toString(),
                "-cp", String.join(File.pathSeparator, cp.stream().map(Path::toString).toList())));
        args.addAll(files);
        System.out.println("  compiling " + what + " (" + files.size() + " file" + (files.size() == 1 ? "" : "s") + ")");
        if (javac.run(null, null, null, args.toArray(String[]::new)) != 0) {
            throw new Miracle.Heresy("The compiler has spoken against " + what + " (see above).");
        }
    }

    private static void copyTree(Path from, Path to) throws IOException {
        if (!Files.isDirectory(from)) {
            return;
        }
        try (Stream<Path> s = Files.walk(from)) {
            for (Path f : s.filter(Files::isRegularFile).toList()) {
                Path dest = to.resolve(from.relativize(f).toString());
                Files.createDirectories(dest.getParent());
                Files.copy(f, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static void jar(Path classes, Path jar) throws IOException {
        Files.createDirectories(jar.getParent());
        Manifest mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        mf.getMainAttributes().putValue("Created-By", "MiracleToolChain");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), mf);
             Stream<Path> s = Files.walk(classes)) {
            for (Path f : s.filter(Files::isRegularFile).sorted().toList()) {
                out.putNextEntry(new JarEntry(classes.relativize(f).toString().replace(File.separatorChar, '/')));
                Files.copy(f, out);
                out.closeEntry();
            }
        }
    }

    static void wipe(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path f : s.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(f);
            }
        }
    }
}
