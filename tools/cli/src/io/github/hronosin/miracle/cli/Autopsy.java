package io.github.hronosin.miracle.cli;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Watches the game's output go by (passing every byte through untouched) for the few deaths
 * worth explaining in plain words: no graphics, no display, a taken port, no memory. The game's
 * own messages for these are a stack trace or a native abort that means nothing to most people.
 */
final class Autopsy {

    /** A known way to die: what the output says, what it means, and what Yukari makes of it. */
    enum Cause {
        NO_GRAPHICS(true, "No usable graphics: the game couldn't get OpenGL (or Vulkan) from the graphics driver. In a virtual"
                + " machine or over Remote Desktop that's normal: give the machine a real GPU driver, use Mesa's software"
                + " OpenGL (on Windows: mesa-dist-win, 'Core desktop OpenGL drivers'), or test with 'miracle pray server',"
                + " which needs no graphics at all.",
                "No context is current", "No supported graphics backend was found", "WGL: The driver does not appear to support OpenGL",
                "Could not create GL context", "Failed to create OpenGL context", "Pixel format not accelerated"),
        NO_DISPLAY(true, "No display to open a window on: this looks like a session without a desktop (SSH, a container, a"
                + " service). Run it from a desktop session, use 'miracle pray server', or, if you like pain, xvfb-run.",
                "Unable to initialize SDL: No available video device", "No X11 DISPLAY variable was set",
                "The DISPLAY environment variable is missing"),
        PORT_TAKEN(false, "The port is taken: something (probably another server, maybe one you forgot) is already listening"
                + " on it. Stop that one, or change server-port in server.properties in the run folder.",
                "FAILED TO BIND TO PORT", "Address already in use"),
        NO_MEMORY(false, "Out of memory. Give the game more before praying: JDK_JAVA_OPTIONS=-Xmx4G (set it in the terminal"
                + " first; on Windows: set JDK_JAVA_OPTIONS=-Xmx4G, or $env:JDK_JAVA_OPTIONS=\"-Xmx4G\" in PowerShell).",
                "java.lang.OutOfMemoryError");

        /** Whether it's a death even when the game exits with 0 (the client does, after "no backend"). */
        final boolean fatal;
        final String meaning;
        final List<String> signs;

        Cause(boolean fatal, String meaning, String... signs) {
            this.fatal = fatal;
            this.meaning = meaning;
            this.signs = List.of(signs);
        }
    }

    private volatile Cause found;

    /** The first known cause seen, or null. */
    Cause cause() {
        return found;
    }

    void see(String line) {
        if (found != null) {
            return;
        }
        for (Cause c : Cause.values()) {
            for (String s : c.signs) {
                if (line.contains(s)) {
                    found = c;
                    return;
                }
            }
        }
    }

    /** Copies {@code in} to {@code out} byte for byte, showing each line to the autopsy on the way. */
    Thread pump(InputStream in, PrintStream out, String name) {
        Thread t = new Thread(() -> {
            byte[] buf = new byte[8192];
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            try (in) {
                for (int n; (n = in.read(buf)) > 0; ) {
                    out.write(buf, 0, n);
                    out.flush();
                    for (int i = 0; i < n; i++) {
                        if (buf[i] == '\n') {
                            see(line.toString(StandardCharsets.ISO_8859_1));
                            line.reset();
                        } else if (line.size() < 4096) {
                            line.write(buf[i]);
                        }
                    }
                }
                if (line.size() > 0) {
                    see(line.toString(StandardCharsets.ISO_8859_1));
                }
            } catch (IOException ignored) {
                // the game is gone; so is its output
            }
        }, name);
        t.setDaemon(true);
        t.start();
        return t;
    }
}
