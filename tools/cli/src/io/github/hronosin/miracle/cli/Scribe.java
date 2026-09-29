package io.github.hronosin.miracle.cli;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@code miracle scribe item|block|entity <name>} (alias {@code assets}): writes the files a new
 * item, block or entity needs besides code: model definitions, models, a placeholder texture, the
 * English name, for blocks a blockstate and a loot table that drops the block, and for entities
 * an empty loot table and a spawn egg (its own placeholder, or a vanilla egg's look with
 * {@code --egg zombie}). Existing files are kept, unless {@code --force}.
 *
 * <p>Paths follow the game's layout since 1.21.4 ({@code assets/<ns>/items/<id>.json} names the
 * model), which is what every supported version reads.
 */
final class Scribe {

    private Scribe() {
    }

    static int run(Path dir, List<String> args, String title, String egg, boolean force) throws IOException {
        if (args.size() < 2 || !List.of("item", "block", "entity").contains(args.get(0))) {
            throw new Miracle.Heresy("scribe what? miracle scribe item <name>, block <name>, or entity <name>");
        }
        if (egg != null && !args.get(0).equals("entity")) {
            throw new Miracle.Heresy("--egg is for entities: their spawn egg can borrow a vanilla one's look");
        }
        if (egg != null && !egg.isEmpty() && !egg.matches("[a-z0-9_]+")) {
            throw new Miracle.Heresy("--egg " + egg + ": a vanilla entity's name, like zombie or pig");
        }
        Project p = Project.find(dir);
        String kind = args.get(0);
        String name = args.get(1);
        if (!name.matches("[a-z0-9_./-]+")) {
            throw new Miracle.Heresy("'" + name + "': ids are lower case, digits, and _ . / - only");
        }
        String ns = p.id().replace('-', '_');
        String id = ns + ":" + name;
        String pretty = title != null ? title : pretty(name);
        Path res = p.dir().resolve("resources");
        Path assets = res.resolve("assets").resolve(ns);
        List<String> wrote = new ArrayList<>();
        List<String> kept = new ArrayList<>();

        if (kind.equals("item")) {
            write(assets.resolve("items/" + name + ".json"), """
                    {
                      "model": { "type": "minecraft:model", "model": "%s:item/%s" }
                    }
                    """.formatted(ns, name), force, wrote, kept, res);
            write(assets.resolve("models/item/" + name + ".json"), """
                    {
                      "parent": "minecraft:item/generated",
                      "textures": { "layer0": "%s:item/%s" }
                    }
                    """.formatted(ns, name), force, wrote, kept, res);
            texture(assets.resolve("textures/item/" + name + ".png"), id, false, force, wrote, kept, res);
        } else if (kind.equals("entity")) {
            String eggName = name + "_spawn_egg";
            if (egg != null && egg.isEmpty()) {
                // --no-egg: a projectile or the like. Just its name.
            } else if (egg != null) {
                write(assets.resolve("items/" + eggName + ".json"), """
                        {
                          "model": { "type": "minecraft:model", "model": "minecraft:item/%s_spawn_egg" }
                        }
                        """.formatted(egg), force, wrote, kept, res);
            } else {
                write(assets.resolve("items/" + eggName + ".json"), """
                        {
                          "model": { "type": "minecraft:model", "model": "%s:item/%s" }
                        }
                        """.formatted(ns, eggName), force, wrote, kept, res);
                write(assets.resolve("models/item/" + eggName + ".json"), """
                        {
                          "parent": "minecraft:item/generated",
                          "textures": { "layer0": "%s:item/%s" }
                        }
                        """.formatted(ns, eggName), force, wrote, kept, res);
                texture(assets.resolve("textures/item/" + eggName + ".png"), id, false, force, wrote, kept, res);
            }
            if (egg == null || !egg.isEmpty()) {
                write(res.resolve("data/" + ns + "/loot_table/entities/" + name + ".json"), """
                        {
                          "type": "minecraft:entity",
                          "pools": []
                        }
                        """, force, wrote, kept, res);
                lang(assets.resolve("lang/en_us.json"), "item." + ns + "." + eggName.replace('/', '.'),
                        pretty + " Spawn Egg", force, wrote, kept, res);
            }
        } else {
            write(assets.resolve("blockstates/" + name + ".json"), """
                    {
                      "variants": { "": { "model": "%s:block/%s" } }
                    }
                    """.formatted(ns, name), force, wrote, kept, res);
            write(assets.resolve("models/block/" + name + ".json"), """
                    {
                      "parent": "minecraft:block/cube_all",
                      "textures": { "all": "%s:block/%s" }
                    }
                    """.formatted(ns, name), force, wrote, kept, res);
            write(assets.resolve("items/" + name + ".json"), """
                    {
                      "model": { "type": "minecraft:model", "model": "%s:block/%s" }
                    }
                    """.formatted(ns, name), force, wrote, kept, res);
            texture(assets.resolve("textures/block/" + name + ".png"), id, true, force, wrote, kept, res);
            write(res.resolve("data/" + ns + "/loot_table/blocks/" + name + ".json"), """
                    {
                      "type": "minecraft:block",
                      "pools": [
                        {
                          "rolls": 1,
                          "entries": [ { "type": "minecraft:item", "name": "%s" } ],
                          "conditions": [ { "condition": "minecraft:survives_explosion" } ]
                        }
                      ]
                    }
                    """.formatted(id), force, wrote, kept, res);
        }
        String prefix = switch (kind) {
            case "item" -> "item.";
            case "block" -> "block.";
            default -> "entity.";
        };
        lang(assets.resolve("lang/en_us.json"), prefix + ns + "." + name.replace('/', '.'), pretty, force, wrote, kept, res);

        wrote.forEach(f -> System.out.println("  wrote " + f));
        kept.forEach(f -> System.out.println("  kept  " + f + " (exists; --force to overwrite)"));
        System.out.println("It is written. " + id + " has its scripture; now make it in code: Creation."
                + kind + "(\"" + name + "\", ...)" + (kind.equals("entity") && !"".equals(egg) ? ".spawnEgg()" : "") + ".");
        return 0;
    }

    private static void write(Path file, String text, boolean force, List<String> wrote, List<String> kept, Path root)
            throws IOException {
        if (Files.exists(file) && !force) {
            kept.add(root.relativize(file).toString());
            return;
        }
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
        wrote.add(root.relativize(file).toString());
    }

    /** Adds one name to en_us.json, keeping the others. */
    private static void lang(Path file, String key, String value, boolean force, List<String> wrote, List<String> kept,
                             Path root) throws IOException {
        Map<String, String> entries = new TreeMap<>();
        if (Files.exists(file)) {
            Json.obj(Json.parse(Files.readString(file))).forEach((k, v) -> entries.put(k, String.valueOf(v)));
            if (entries.containsKey(key) && !force) {
                kept.add(root.relativize(file) + " [" + key + "]");
                return;
            }
        }
        entries.put(key, value);
        StringBuilder sb = new StringBuilder("{\n");
        int i = 0;
        for (var e : entries.entrySet()) {
            sb.append("  ").append(quote(e.getKey())).append(": ").append(quote(e.getValue()))
                    .append(++i < entries.size() ? ",\n" : "\n");
        }
        sb.append("}\n");
        Files.createDirectories(file.getParent());
        Files.writeString(file, sb);
        wrote.add(root.relativize(file) + " [" + key + "]");
    }

    /**
     * A 16x16 placeholder, coloured by the id so two of them never look alike: a gem for items,
     * a framed tile for blocks. Replace it with real art whenever you like.
     */
    private static void texture(Path file, String id, boolean block, boolean force, List<String> wrote, List<String> kept,
                                Path root) throws IOException {
        if (Files.exists(file) && !force) {
            kept.add(root.relativize(file).toString());
            return;
        }
        int h = id.hashCode();
        int r = 96 + Math.floorMod(h, 160);
        int g = 96 + Math.floorMod(h >> 8, 160);
        int b = 96 + Math.floorMod(h >> 16, 160);
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                int argb;
                if (block) {
                    boolean edge = x == 0 || y == 0 || x == 15 || y == 15;
                    int shade = edge ? 60 : ((x * 7 + y * 13 + h) & 15) * 3;
                    argb = rgb(r - shade, g - shade, b - shade);
                } else {
                    int d = Math.abs(x - 7) + Math.abs(y - 7);  // a diamond, as all good things are
                    argb = d > 6 ? 0 : d == 6 ? rgb(r / 3, g / 3, b / 3) : rgb(r + (6 - d) * 8, g + (6 - d) * 8, b + (6 - d) * 8);
                }
                img.setRGB(x, y, argb);
            }
        }
        Files.createDirectories(file.getParent());
        ImageIO.write(img, "png", file.toFile());
        wrote.add(root.relativize(file).toString());
    }

    private static int rgb(int r, int g, int b) {
        return 0xFF000000 | clamp(r) << 16 | clamp(g) << 8 | clamp(b);
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    private static String pretty(String name) {
        String last = name.substring(name.lastIndexOf('/') + 1);
        StringBuilder sb = new StringBuilder();
        for (String w : last.split("[_.-]")) {
            if (!w.isEmpty()) {
                sb.append(sb.isEmpty() ? "" : " ").append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
            }
        }
        return sb.toString();
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }
}
