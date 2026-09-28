package io.github.hronosin.miracle;

/** Deliberately tiny logger. The game brings its own; we only need to talk before it starts. */
public final class Log {

    private Log() {
    }

    public static void info(String msg) {
        System.out.println("[Miracle] " + msg);
    }

    public static void warn(String msg) {
        System.out.println("[Miracle/WARN] " + msg);
    }

    public static void error(String msg) {
        System.err.println("[Miracle/ERROR] " + msg);
    }
}
