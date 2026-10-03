package io.github.hronosin.miracle;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * The last word. When the game ends the way it should (the player quit, the server stopped), the
 * loader's last line is {@code Cool :D}. Not after a crash (the crash banner, or a fresh report in
 * {@code crash-reports}), and not when the process was told to stop by a signal (a launcher's
 * kill button). The launcher's own "exited with code 0" comes after, from the launcher.
 *
 * <p>{@code -Dmiracle.cool=false} keeps quiet.
 */
final class Farewell {

    private static final long STARTED = System.currentTimeMillis();
    /** The real standard output, kept before the game hands System.out to its logger. */
    private static final java.io.PrintStream OUT = new java.io.PrintStream(
            new java.io.FileOutputStream(java.io.FileDescriptor.out), true);
    private static volatile boolean crashed;
    private static volatile boolean signalled;
    private static volatile boolean armed;

    private Farewell() {
    }

    /** Something went wrong: no farewell. */
    static void crashed() {
        crashed = true;
    }

    static synchronized void arm() {
        if (armed || "false".equals(System.getProperty("miracle.cool"))) {
            return;
        }
        armed = true;
        for (String name : new String[] {"TERM", "INT", "HUP"}) {
            watch(name);
        }
        Runtime.getRuntime().addShutdownHook(new Thread(Farewell::bye, "miracle-farewell"));
    }

    private static void bye() {
        if (crashed || signalled) {
            return;
        }
        // The built-in server may still be saving the world as the game exits: the last word waits for it.
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.getName().equals("Server thread") && t != Thread.currentThread()) {
                try {
                    t.join(5000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        if (crashed || freshCrashReport()) {
            return;
        }
        OUT.println("Cool :D");
    }

    /** A crash report written since the game started: the game crashed on its own terms. */
    private static boolean freshCrashReport() {
        Path reports = Backend.gameDir().resolve("crash-reports");
        if (!Files.isDirectory(reports)) {
            return false;
        }
        try (Stream<Path> files = Files.list(reports)) {
            return files.anyMatch(f -> {
                try {
                    return Files.getLastModifiedTime(f).toMillis() >= STARTED;
                } catch (IOException e) {
                    return false;
                }
            });
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Notes that the process was told to stop by this signal, then lets whatever handled it before
     * handle it as before. Through reflection: sun.misc.Signal is unsupported API, and may be absent.
     */
    private static void watch(String name) {
        try {
            Class<?> signal = Class.forName("sun.misc.Signal");
            Class<?> handler = Class.forName("sun.misc.SignalHandler");
            Object sig = signal.getConstructor(String.class).newInstance(name);
            Object[] previous = new Object[1];
            Object mine = Proxy.newProxyInstance(handler.getClassLoader(), new Class<?>[] {handler}, (proxy, method, args) -> {
                switch (method.getName()) {
                    case "equals" -> {
                        return proxy == args[0];
                    }
                    case "hashCode" -> {
                        return System.identityHashCode(proxy);
                    }
                    case "toString" -> {
                        return "miracle-farewell";
                    }
                    case "handle" -> {
                    }
                    default -> {
                        return null;
                    }
                }
                signalled = true;
                try {
                    handler.getMethod("handle", signal).invoke(previous[0], args[0]);
                } catch (ReflectiveOperationException | RuntimeException notCallable) {
                    // the default action, which isn't an object you can call: the usual exit code
                    int number = (int) signal.getMethod("getNumber").invoke(args[0]);
                    System.exit(128 + number);
                }
                return null;
            });
            previous[0] = signal.getMethod("handle", signal, handler).invoke(null, sig, mine);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            // no such signal here (Windows has no HUP), or no sun.misc at all: then signals say goodbye too
        }
    }
}
