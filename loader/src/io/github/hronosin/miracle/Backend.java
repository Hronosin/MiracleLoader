package io.github.hronosin.miracle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Which graphics backend the game is going to use, known before it starts: the player's word
 * ({@code -Dmiracle.backend=opengl|vulkan}) or, by default, the game's own setting
 * ({@code preferredGraphicsBackend} in {@code options.txt}: "opengl", "vulkan" or "default", the
 * last meaning the game picks when it starts). Vulkan exists from 26.2 on; older versions are
 * OpenGL whatever the file says. A server draws nothing: "none".
 */
final class Backend {

    /** The game's arguments, when the loader has them (MiracleMain; the agent reads the command line). */
    static volatile List<String> gameArgs = List.of();

    private Backend() {
    }

    static String detect(String gameVersion, boolean client) {
        if (!client) {
            return "none";
        }
        String said = System.getProperty("miracle.backend", "auto").strip().toLowerCase(java.util.Locale.ROOT);
        if (!List.of("auto", "opengl", "vulkan").contains(said)) {
            throw new MiracleFailure("-Dmiracle.backend=" + said + ": that's auto (the game's own setting), opengl or vulkan.");
        }
        String choice;
        String how;
        if (!said.equals("auto")) {
            choice = said;
            how = "-Dmiracle.backend";
        } else {
            Path options = gameDir().resolve("options.txt");
            String fromFile = setting(options);
            choice = fromFile == null ? "default" : fromFile;
            how = fromFile == null ? (Files.exists(options) ? "not set in options.txt" : "no options.txt yet") : "options.txt";
        }
        boolean vulkanHere = Dependencies.compare(gameVersion, "26.2") >= 0;
        if (!vulkanHere && !choice.equals("opengl")) {
            how += "; this version has OpenGL only";
            choice = "opengl";
        }
        Log.info("Graphics: " + switch (choice) {
            case "opengl" -> "OpenGL";
            case "vulkan" -> "Vulkan";
            default -> "the game's own pick at start (OpenGL or Vulkan)";
        } + " (" + how + ")");
        return choice;
    }

    /** {@code preferredGraphicsBackend} from options.txt, or null. */
    static String setting(Path options) {
        try {
            for (String line : Files.readAllLines(options)) {
                if (line.startsWith("preferredGraphicsBackend:")) {
                    String v = line.substring(line.indexOf(':') + 1).strip().replace("\"", "").toLowerCase(java.util.Locale.ROOT);
                    return List.of("opengl", "vulkan", "default").contains(v) ? v : null;
                }
            }
        } catch (IOException | RuntimeException e) {
            // no file, or not one we can read: the game's own default
        }
        return null;
    }

    /** {@code --gameDir} from the game's arguments, or the working folder. */
    static Path gameDir() {
        List<String> args = gameArgs;
        if (args.isEmpty()) {
            String cmd = System.getProperty("sun.java.command", "");
            args = List.of(cmd.split(" "));
        }
        int i = args.indexOf("--gameDir");
        if (i >= 0 && i + 1 < args.size()) {
            return Path.of(args.get(i + 1));
        }
        return Path.of("");
    }
}
