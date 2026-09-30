package io.github.hronosin.miracle;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which Minecraft is running, read from the {@code version.json} every game jar carries.
 *
 * <p>{@code obfuscated} is true for versions whose class names are scrambled (1.21.11 and older):
 * there, mods need a variant baked for exactly that version. Override with
 * {@code -Dmiracle.gameVersion=...} and {@code -Dmiracle.obfuscated=true|false}.
 */
record GameVersion(String id, boolean obfuscated) {

    static final String UNKNOWN = "unknown";

    private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");

    static GameVersion detect(Host game) {
        String id = System.getProperty("miracle.gameVersion");
        URL versionJson = game.findResource("version.json");
        if (id == null && versionJson != null) {
            try (InputStream in = versionJson.openStream()) {
                Matcher m = ID.matcher(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                if (m.find()) {
                    id = m.group(1);
                }
            } catch (IOException e) {
                Log.warn("Could not read version.json: " + e.getMessage());
            }
        }
        if (id == null) {
            id = UNKNOWN;
        }

        String forced = System.getProperty("miracle.obfuscated");
        boolean obfuscated;
        if (forced != null) {
            obfuscated = Boolean.parseBoolean(forced);
        } else {
            // A real Minecraft jar without its readable class names is an obfuscated one.
            obfuscated = versionJson != null
                    && game.findResource("net/minecraft/world/entity/LivingEntity.class") == null;
        }
        return new GameVersion(id, obfuscated);
    }

    String describe() {
        return "Minecraft " + id + (obfuscated ? " (obfuscated)" : "");
    }
}
