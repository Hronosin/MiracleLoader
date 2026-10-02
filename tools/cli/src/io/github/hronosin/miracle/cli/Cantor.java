package io.github.hronosin.miracle.cli;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The cantor: {@code miracle scribe sound <name> <file>} brings a sound into a mod. The game plays
 * Ogg Vorbis only, and plays a sound from a place in the world only if it's mono (stereo sounds
 * are heard the same everywhere). So: an Ogg Vorbis file that already fits is copied as it is;
 * anything else (wav, mp3, flac, Opus...) goes through ffmpeg (or oggenc for wav/flac) into Ogg
 * Vorbis, mono for sounds, stereo for {@code --music}. Then {@code sounds.json} gets the event,
 * {@code en_us.json} its subtitle. Running it again with the same name adds another variant,
 * and the game picks one at random each time.
 */
final class Cantor {

    private Cantor() {
    }

    /** What an Ogg file holds, from its first page. */
    record Ogg(String codec, int channels) {
    }

    static int run(Path dir, String name, Path source, boolean music, String title, boolean force) throws IOException {
        if (!name.matches("[a-z0-9_./-]+")) {
            throw new Miracle.Heresy("'" + name + "': sound names are lower case, digits, and _ . / - only");
        }
        if (!Files.isRegularFile(source)) {
            throw new Miracle.Heresy("No such file: " + source + ". miracle scribe sound <name> <file>, the file being your sound.");
        }
        Project p = Project.find(dir);
        String ns = p.id().replace('-', '_');
        String event = name.replace('/', '.');
        Path res = p.dir().resolve("resources");
        Path assets = res.resolve("assets").resolve(ns);
        Path sounds = assets.resolve("sounds");
        Path soundsJson = assets.resolve("sounds.json");

        Map<String, Object> json = new LinkedHashMap<>();
        if (Files.exists(soundsJson)) {
            json.putAll(Json.obj(Json.parse(Files.readString(soundsJson))));
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        List<Object> variants = new ArrayList<>();
        if (json.containsKey(event) && !force) {
            entry.putAll(Json.obj(json.get(event)));
            Object had = entry.get("sounds");
            if (had != null) {
                variants.addAll(Json.arr(had));
            }
        }

        // where it goes: name.ogg, or name_2.ogg, name_3.ogg... for more variants
        String file = name;
        if (!force) {
            for (int n = 2; Files.exists(sounds.resolve(file + ".ogg")); n++) {
                file = name + "_" + n;
            }
        }
        Path target = sounds.resolve(file + ".ogg");
        Files.createDirectories(target.getParent());
        String how = bring(source, target, music);

        Map<String, Object> variant = new LinkedHashMap<>();
        variant.put("name", ns + ":" + file);
        if (music) {
            variant.put("stream", true);
        }
        variants.removeIf(v -> v instanceof Map<?, ?> m && variant.get("name").equals(m.get("name"))
                || v instanceof String s && s.equals(variant.get("name")));
        variants.add(variant);
        entry.put("sounds", variants);
        String subtitleKey = "subtitles." + ns + "." + event;
        boolean subtitle = !music || title != null;
        if (subtitle) {
            entry.put("subtitle", subtitleKey);
        }
        json.put(event, entry);
        Files.writeString(soundsJson, Json.write(json) + "\n");

        Path root = res;
        System.out.println("  wrote " + root.relativize(target) + " (" + how + ")");
        System.out.println("  wrote " + root.relativize(soundsJson) + " [" + event + ", " + variants.size() + " variant"
                + (variants.size() == 1 ? "" : "s") + "]");
        if (subtitle) {
            List<String> wrote = new ArrayList<>();
            List<String> kept = new ArrayList<>();
            Scribe.lang(assets.resolve("lang/en_us.json"), subtitleKey, title != null ? title : Scribe.pretty(name.substring(name.lastIndexOf('/') + 1)),
                    force || title != null, wrote, kept, root);
            wrote.forEach(f -> System.out.println("  wrote " + f));
            kept.forEach(f -> System.out.println("  kept  " + f + " (exists; --title to change it)"));
        }
        String id = ns + ":" + event;
        System.out.println("It is sung. " + id + " is a sound now: /playsound " + id + " master @s, or in code"
                + (music ? " Chirp.music(player, \"" + id + "\")" : " Chirp.play(level, where, \"" + id + "\")")
                + " (Event Horizon).");
        return 0;
    }

    /** Copies or converts {@code source} into Ogg Vorbis at {@code target}; says how. */
    static String bring(Path source, Path target, boolean music) throws IOException {
        Ogg ogg = probe(source);
        int want = music ? 2 : 1;
        if (ogg != null && ogg.codec.equals("vorbis") && (ogg.channels == 1 || music)) {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            return "Ogg Vorbis, " + channels(ogg.channels) + ", copied as it is";
        }
        String why = ogg == null ? "converted to Ogg Vorbis"
                : ogg.codec.equals("vorbis") ? "stereo made mono: only mono sounds come from a place in the world"
                : "Ogg " + Character.toUpperCase(ogg.codec.charAt(0)) + ogg.codec.substring(1)
                        + " converted to Vorbis: the game plays Vorbis only";
        Path tmp = target.resolveSibling(target.getFileName() + ".part");
        try {
            List<String> failures = new ArrayList<>();
            if (encode(ffmpeg(source, tmp, want, "libvorbis"), failures) || encode(ffmpeg(source, tmp, want, "vorbis"), failures)
                    || encode(oggenc(source, tmp, want), failures)) {
                Ogg made = probe(tmp);
                if (made == null || !made.codec.equals("vorbis")) {
                    throw new Miracle.Heresy("The encoder made something, but not Ogg Vorbis. Convert " + source.getFileName()
                            + " to .ogg (Vorbis) yourself and try again.");
                }
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                return why + (ogg != null && ogg.codec.equals("vorbis") ? "" : ", " + channels(made.channels));
            }
            throw new Miracle.Heresy("Can't convert " + source.getFileName() + ": that needs ffmpeg (or oggenc, for wav and flac),"
                    + " and " + (failures.isEmpty() ? "neither is installed" : String.join("; ", failures)) + ".\n"
                    + "  Fedora: sudo dnf install ffmpeg-free; Debian/Ubuntu: sudo apt install ffmpeg;"
                    + " Windows: winget install Gyan.FFmpeg; macOS: brew install ffmpeg.\n"
                    + "  Or give it an .ogg file that's already Vorbis (and mono, unless it's --music).");
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static List<String> ffmpeg(Path in, Path out, int channels, String codec) {
        List<String> cmd = new ArrayList<>(List.of("ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin", "-y",
                "-i", in.toString(), "-vn", "-map_metadata", "-1", "-ac", String.valueOf(channels), "-c:a", codec));
        if (codec.equals("vorbis")) {
            cmd.addAll(List.of("-strict", "experimental"));
        } else {
            cmd.addAll(List.of("-q:a", "5"));
        }
        cmd.addAll(List.of("-f", "ogg", out.toString()));
        return cmd;
    }

    private static List<String> oggenc(Path in, Path out, int channels) {
        String n = in.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!(n.endsWith(".wav") || n.endsWith(".flac") || n.endsWith(".aif") || n.endsWith(".aiff"))) {
            return null;
        }
        List<String> cmd = new ArrayList<>(List.of("oggenc", "-Q", "-q", "5", "-o", out.toString()));
        if (channels == 1) {
            cmd.add("--downmix");
        }
        cmd.add(in.toString());
        return cmd;
    }

    /** Runs an encoder; false (with why) if it's missing or fails. */
    private static boolean encode(List<String> cmd, List<String> failures) {
        if (cmd == null) {
            return false;
        }
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out;
            try (InputStream in = p.getInputStream()) {
                out = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            }
            if (!p.waitFor(10, TimeUnit.MINUTES)) {
                p.destroyForcibly();
                failures.add(cmd.get(0) + " took too long");
                return false;
            }
            if (p.exitValue() == 0) {
                return true;
            }
            String last = out.isEmpty() ? "exit code " + p.exitValue() : out.lines().reduce((a, b) -> b).orElse("");
            failures.add(cmd.get(0) + " failed (" + last + ")");
            return false;
        } catch (IOException notInstalled) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** The codec and channels of an Ogg file, from its first packet; null if it isn't Ogg. */
    static Ogg probe(Path file) throws IOException {
        byte[] head = new byte[512];
        int n;
        try (InputStream in = Files.newInputStream(file)) {
            n = in.readNBytes(head, 0, head.length);
        }
        if (n < 28 || head[0] != 'O' || head[1] != 'g' || head[2] != 'g' || head[3] != 'S') {
            return null;
        }
        int segments = head[26] & 0xFF;
        int at = 27 + segments;
        if (at + 16 > n) {
            return new Ogg("unknown", 0);
        }
        if (head[at] == 1 && new String(head, at + 1, 6, StandardCharsets.ISO_8859_1).equals("vorbis")) {
            return new Ogg("vorbis", head[at + 11] & 0xFF);
        }
        if (new String(head, at, 8, StandardCharsets.ISO_8859_1).equals("OpusHead")) {
            return new Ogg("opus", head[at + 9] & 0xFF);
        }
        if (new String(head, at + 1, 4, StandardCharsets.ISO_8859_1).equals("FLAC")) {
            return new Ogg("flac", 0);
        }
        if (new String(head, at, 8, StandardCharsets.ISO_8859_1).equals("Speex   ")) {
            return new Ogg("speex", 0);
        }
        return new Ogg("unknown", 0);
    }

    private static String channels(int n) {
        return n == 1 ? "mono" : n == 2 ? "stereo" : n + " channels";
    }
}
