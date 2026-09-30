package io.github.hronosin.miracle;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The game's main class as launchers see it, and the only class in MiracleLoader compiled for
 * Java 8, so that any Java can run it.
 *
 * <p>MiracleLoader needs Java 25: RGCT is built on the JDK's ClassFile API, which older Javas
 * don't have, so its bytecode can't be downgraded. A launcher, though, starts each Minecraft
 * version with the Java that version asks for: 21 for 1.21.11. So on a Java older than 25 this
 * class finds a Java 25 or newer on the machine and resurrects the game in it: the same JVM
 * options, class path and arguments, one process further down. It then stays as a thin shepherd:
 * the game's output passes straight through, closing the launcher's process closes the game,
 * and the game's exit code is its own. On Java 25 or newer it just hands over to
 * {@code MiracleMain}, in the same process.
 *
 * <p>Where it looks, in order: {@code -Dmiracle.java} or {@code MIRACLE_JAVA} (a Java home or a
 * {@code java} binary), {@code JAVA_HOME}, the {@code PATH}, then the usual places (system JVM
 * folders, SDKMAN, IntelliJ's {@code ~/.jdks}, Prism Launcher's and the official launcher's
 * downloaded runtimes, Adoptium, Zulu and Microsoft on Windows, macOS's JavaVirtualMachines).
 * {@code -Dmiracle.javaSearch=explicit} stops after the first two.
 */
public final class Resurrection {

    static final int NEEDED = 25;
    private static final String MAIN = "io.github.hronosin.miracle.MiracleMain";

    private Resurrection() {
    }

    public static void main(String[] args) throws Throwable {
        int running = major(System.getProperty("java.specification.version"));
        if (running >= NEEDED) {
            handOver(args);
            return;
        }
        if (System.getProperty("miracle.resurrected") != null) {
            // Relaunched, and still too old: the Java we picked lied about its version.
            die("[Miracle] Resurrected in Java " + running + ", which is still older than " + NEEDED + ". Giving up before this"
                    + " turns into a loop.");
        }
        String asked = askedJava();
        say("[Miracle] This game was started with Java " + running + (asked == null ? "" : " (" + asked + ")")
                + ", and MiracleLoader needs Java " + NEEDED + " or newer.");
        List<Candidate> found = search();
        Candidate best = null;
        for (Candidate c : found) {
            if (c.major >= NEEDED && (best == null || c.major > best.major)) {
                best = c;
            }
        }
        if (best == null) {
            StringBuilder sb = new StringBuilder();
            sb.append("[Miracle] No Java ").append(NEEDED).append(" or newer was found, so there is no miracle today.\n");
            if (!found.isEmpty()) {
                sb.append("  Found, but too old:");
                for (Candidate c : found) {
                    sb.append("\n    Java ").append(c.major).append("  ").append(c.java);
                }
                sb.append('\n');
            }
            sb.append("  Install Java ").append(NEEDED).append(" (for example from adoptium.net), then either:\n")
                    .append("    - in Prism Launcher: Edit instance > Settings > Java > pick the Java ").append(NEEDED)
                    .append(" installation (the game then starts in it directly), or\n")
                    .append("    - leave the launcher's Java alone: MiracleLoader finds Java ").append(NEEDED)
                    .append(" in the usual places by itself, or where MIRACLE_JAVA=/path/to/jdk-").append(NEEDED)
                    .append(" points.");
            die(sb.toString());
        }
        say("[Miracle] Resurrecting the game in Java " + best.major + ": " + best.java);
        System.exit(relaunch(best.java, args));
    }

    /** Java 25 or newer: straight into the loader, in this process. */
    private static void handOver(String[] args) throws Throwable {
        Method main = Class.forName(MAIN).getMethod("main", String[].class);
        try {
            main.invoke(null, (Object) args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    // --- the new body --------------------------------------------------------------------------

    private static int relaunch(File java, String[] args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<String>();
        cmd.add(java.getPath());
        // Options that meant something to the old Java may not exist in the new one (old GC
        // flags, say); the new one should shrug them off rather than refuse to start.
        cmd.add("-XX:+IgnoreUnrecognizedVMOptions");
        for (String a : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (!a.startsWith("-Dmiracle.resurrected")) {
                cmd.add(a);
            }
        }
        cmd.add("-Dmiracle.resurrected=" + System.getProperty("java.specification.version"));
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.add(MAIN);
        cmd.addAll(Arrays.asList(args));
        if (Boolean.getBoolean("miracle.showCommand")) {
            say("[Miracle] " + cmd);
        }
        ProcessBuilder pb = new ProcessBuilder(cmd).inheritIO();
        final Process game = pb.start();
        // The launcher stops the game by stopping us; pass it on.
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                if (isAlive(game)) {
                    game.destroy();
                }
            }
        }, "miracle-shepherd"));
        return game.waitFor();
    }

    private static boolean isAlive(Process p) {
        try {
            p.exitValue();
            return false;
        } catch (IllegalThreadStateException e) {
            return true;
        }
    }

    // --- looking for a Java --------------------------------------------------------------------

    /** A {@code java} binary and the major version it is. */
    static final class Candidate {
        final File java;
        final int major;

        Candidate(File java, int major) {
            this.java = java;
            this.major = major;
        }
    }

    static List<Candidate> search() {
        Set<File> binaries = new LinkedHashSet<File>();
        String explicit = System.getProperty("miracle.java");
        if (explicit == null || explicit.trim().isEmpty()) {
            explicit = System.getenv("MIRACLE_JAVA");
        }
        if (explicit != null && !explicit.trim().isEmpty()) {
            addHomeOrBinary(binaries, new File(explicit.trim()));
        }
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null && !javaHome.isEmpty()) {
            addHomeOrBinary(binaries, new File(javaHome));
        }
        if (!"explicit".equals(System.getProperty("miracle.javaSearch"))) {
            String path = System.getenv("PATH");
            if (path != null) {
                for (String dir : path.split(File.pathSeparator)) {
                    addBinary(binaries, new File(dir, exe()));
                }
            }
            for (File root : roots()) {
                scan(binaries, root, 0);
            }
        }
        List<Candidate> out = new ArrayList<Candidate>();
        Set<String> seen = new LinkedHashSet<String>();
        for (File bin : binaries) {
            String key;
            try {
                key = bin.getCanonicalPath();
            } catch (IOException e) {
                key = bin.getAbsolutePath();
            }
            if (!seen.add(key)) {
                continue;
            }
            int major = version(bin);
            if (major > 0) {
                out.add(new Candidate(bin, major));
            }
        }
        return out;
    }

    private static List<File> roots() {
        String home = System.getProperty("user.home", "");
        List<File> r = new ArrayList<File>();
        String[] unix = {
            "/usr/lib/jvm", "/usr/lib64/jvm", "/usr/java", "/usr/local/java", "/opt", "/opt/java", "/opt/jdk",
            "/Library/Java/JavaVirtualMachines",
            home + "/Library/Java/JavaVirtualMachines",
            home + "/.jdks", home + "/.sdkman/candidates/java", home + "/.local/share/PrismLauncher/java",
            home + "/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java",
            home + "/.local/share/multimc/java", home + "/.minecraft/runtime",
            home + "/Library/Application Support/PrismLauncher/java",
            home + "/Library/Application Support/minecraft/runtime",
        };
        for (String s : unix) {
            r.add(new File(s));
        }
        String[] winEnv = {"ProgramFiles", "ProgramW6432", "ProgramFiles(x86)"};
        for (String v : winEnv) {
            String pf = System.getenv(v);
            if (pf != null) {
                for (String vendor : new String[]{"Java", "Eclipse Adoptium", "Zulu", "Microsoft", "BellSoft", "Amazon Corretto",
                        "Eclipse Foundation", "OpenJDK"}) {
                    r.add(new File(pf, vendor));
                }
            }
        }
        String appData = System.getenv("APPDATA");
        if (appData != null) {
            r.add(new File(appData, "PrismLauncher/java"));
            r.add(new File(appData, ".minecraft/runtime"));
        }
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null) {
            r.add(new File(localAppData, "Packages/Microsoft.4297127D64EC6_8wekyb3d8bbwe/LocalCache/Local/runtime"));
        }
        return r;
    }

    /** Looks a few folders deep for {@code bin/java}: runtimes nest (Prism: java/<name>/bin, macOS: Contents/Home). */
    private static void scan(Set<File> out, File dir, int depth) {
        if (depth > 4 || !dir.isDirectory()) {
            return;
        }
        File bin = new File(new File(dir, "bin"), exe());
        if (bin.isFile()) {
            out.add(bin);
            return;
        }
        File[] kids = dir.listFiles();
        if (kids == null) {
            return;
        }
        Arrays.sort(kids);
        for (File k : kids) {
            if (k.isDirectory() && !k.getName().startsWith(".")) {
                scan(out, k, depth + 1);
            }
        }
    }

    private static void addHomeOrBinary(Set<File> out, File f) {
        if (f.isFile()) {
            out.add(f);
        } else {
            addBinary(out, new File(new File(f, "bin"), exe()));
            addBinary(out, new File(new File(new File(f, "Contents"), "Home"), "bin" + File.separator + exe()));
        }
    }

    private static void addBinary(Set<File> out, File f) {
        if (f.isFile()) {
            out.add(f);
        }
    }

    private static String exe() {
        return System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java";
    }

    /** The major version of a Java: from its {@code release} file, or by asking it. 0 if neither works. */
    static int version(File java) {
        File home = java.getAbsoluteFile().getParentFile() == null ? null : java.getAbsoluteFile().getParentFile().getParentFile();
        if (home != null) {
            File release = new File(home, "release");
            if (release.isFile()) {
                Properties p = new Properties();
                InputStream in = null;
                try {
                    in = new FileInputStream(release);
                    p.load(in);
                    String v = p.getProperty("JAVA_VERSION");
                    if (v != null) {
                        return major(v.replace("\"", ""));
                    }
                } catch (IOException e) {
                    // ask it instead
                } finally {
                    close(in);
                }
            }
        }
        try {
            Process p = new ProcessBuilder(java.getPath(), "-XshowSettings:properties", "-version").redirectErrorStream(true).start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), Charset.defaultCharset()));
            String line;
            int major = 0;
            Pattern spec = Pattern.compile("java\\.specification\\.version = (\\S+)");
            while ((line = r.readLine()) != null) {
                Matcher m = spec.matcher(line);
                if (m.find()) {
                    major = major(m.group(1));
                }
            }
            p.waitFor();
            return major;
        } catch (IOException e) {
            return 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        }
    }

    /** "1.8" is 8, "21" is 21, "25.0.1" is 25. */
    static int major(String v) {
        if (v == null) {
            return 0;
        }
        String[] parts = v.trim().split("[.+_-]");
        try {
            int first = Integer.parseInt(parts[0]);
            if (first == 1 && parts.length > 1) {
                return Integer.parseInt(parts[1]);
            }
            return first;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** What the game itself asks for: {@code java_version} in its version.json, if it's on the class path. */
    private static String askedJava() {
        InputStream in = Resurrection.class.getClassLoader().getResourceAsStream("version.json");
        if (in == null) {
            return null;
        }
        try {
            byte[] buf = new byte[65536];
            int n = 0;
            for (int r; n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0; ) {
                n += r;
            }
            String json = new String(buf, 0, n, "UTF-8");
            Matcher id = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
            Matcher jv = Pattern.compile("\"java_version\"\\s*:\\s*(\\d+)").matcher(json);
            if (id.find() && jv.find()) {
                return "Minecraft " + id.group(1) + " asks for Java " + jv.group(1);
            }
            return null;
        } catch (IOException e) {
            return null;
        } finally {
            close(in);
        }
    }

    private static void close(InputStream in) {
        if (in != null) {
            try {
                in.close();
            } catch (IOException ignored) {
                // nothing to do
            }
        }
    }

    private static void say(String s) {
        System.out.println(s);
    }

    private static void die(String s) {
        System.err.println(s);
        System.exit(1);
    }
}
