package io.github.hronosin.miracle.toolchain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Entity geometry in Bedrock's format ({@code .geo.json}, what Blockbench exports for "Bedrock
 * Entity" and "Generic Model" projects), read and turned into the shapes Java Edition's models
 * are made of. Pure data, no game classes: {@link Sculptor} builds the game's model from it.
 *
 * <p>The two editions measure differently. Bedrock puts the origin at the feet with Y up, and
 * every coordinate is absolute; Java puts it 24 pixels above the feet with Y down, and a part's
 * boxes are relative to its pivot. Rotations carry over as they are (Blockbench writes both
 * formats from one model: it negates X and Y rotations for each, so they agree). Bones become parts, cubes become boxes, and a cube with a
 * rotation of its own becomes a small part of its own, since Java boxes can't rotate.
 *
 * <p>Supported: bones with parents, pivots and rotations; cubes with box UV, {@code inflate} and
 * {@code mirror}; cube rotations and pivots. Per-face UV is read as box UV from its north face
 * (Java models have no per-face UV), with a warning in {@link Model#notes}.
 */
final class Clay {

    /** A box, in Java's terms: offset from its part's pivot, Y down. */
    record Box(int u, int v, float x, float y, float z, float w, float h, float d, float inflate, boolean mirror) {
    }

    /** A part: its pose relative to its parent (radians), its boxes, and its children. */
    record Part(String name, float x, float y, float z, float xRot, float yRot, float zRot,
                List<Box> boxes, List<Part> children) {
    }

    /** A whole model: the texture's size, the top-level parts, and anything worth telling. */
    record Model(int textureWidth, int textureHeight, List<Part> parts, List<String> notes) {
    }

    private static final float DEG = (float) (Math.PI / 180);

    private Clay() {
    }

    /** Reads the first geometry of a {@code .geo.json}. Throws with a readable reason if it can't. */
    static Model read(String json) {
        Object root = Json.parse(json);
        Object geometries = Json.get(root, "minecraft:geometry");
        Map<?, ?> geo;
        if (geometries instanceof List<?> l && !l.isEmpty() && l.getFirst() instanceof Map<?, ?> m) {
            geo = m;
        } else {
            throw new IllegalArgumentException("no \"minecraft:geometry\" in it (a Bedrock geometry file from Blockbench?)");
        }
        int tw = (int) Json.num(Json.get(geo, "description", "texture_width"), 64);
        int th = (int) Json.num(Json.get(geo, "description", "texture_height"), 64);
        List<String> notes = new ArrayList<>();

        // Bones by name, then each placed under its parent (bones may come in any order).
        Map<String, Map<?, ?>> bones = new LinkedHashMap<>();
        if (Json.get(geo, "bones") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> b && Json.get(b, "name") instanceof String name) {
                    if (bones.put(name, b) != null) {
                        throw new IllegalArgumentException("two bones are called '" + name + "'");
                    }
                }
            }
        }
        if (bones.isEmpty()) {
            throw new IllegalArgumentException("the geometry has no bones");
        }
        Map<String, List<String>> kids = new LinkedHashMap<>();
        List<String> tops = new ArrayList<>();
        for (var e : bones.entrySet()) {
            Object parent = Json.get(e.getValue(), "parent");
            if (parent instanceof String p && bones.containsKey(p)) {
                kids.computeIfAbsent(p, k -> new ArrayList<>()).add(e.getKey());
            } else {
                if (parent instanceof String p) {
                    notes.add("bone '" + e.getKey() + "' has parent '" + p + "', which doesn't exist; it hangs from the root");
                }
                tops.add(e.getKey());
            }
        }
        List<Part> parts = new ArrayList<>();
        for (String top : tops) {
            parts.add(part(top, null, bones, kids, notes, 0));
        }
        return new Model(tw, th, List.copyOf(parts), List.copyOf(notes));
    }

    private static Part part(String name, float[] parentPivot, Map<String, Map<?, ?>> bones,
                             Map<String, List<String>> kids, List<String> notes, int depth) {
        if (depth > 64) {
            throw new IllegalArgumentException("bone '" + name + "' is its own ancestor");
        }
        Map<?, ?> bone = bones.get(name);
        float[] pivot = vec(Json.get(bone, "pivot"));
        float[] rot = vec(Json.get(bone, "rotation"));
        boolean boneMirror = Json.get(bone, "mirror") instanceof Boolean m && m;
        float boneInflate = (float) Json.num(Json.get(bone, "inflate"), 0);

        List<Box> boxes = new ArrayList<>();
        List<Part> children = new ArrayList<>();
        if (Json.get(bone, "cubes") instanceof List<?> cubes) {
            int i = 0;
            for (Object c : cubes) {
                if (!(c instanceof Map<?, ?> cube)) {
                    continue;
                }
                float[] origin = vec(Json.get(cube, "origin"));
                float[] size = vec(Json.get(cube, "size"));
                int[] uv = uv(Json.get(cube, "uv"), name, notes);
                float inflate = (float) Json.num(Json.get(cube, "inflate"), boneInflate);
                boolean mirror = Json.get(cube, "mirror") instanceof Boolean m ? m : boneMirror;
                Object cr = Json.get(cube, "rotation");
                if (cr != null && !isZero(vec(cr))) {
                    // Java boxes can't rotate: this one gets a part of its own, turned about the cube's pivot.
                    float[] cp = Json.get(cube, "pivot") != null ? vec(Json.get(cube, "pivot")) : pivot;
                    float[] r = vec(cr);
                    Box box = box(uv, origin, size, cp, inflate, mirror);
                    children.add(new Part(name + "_r" + (++i), cp[0] - pivot[0], -(cp[1] - pivot[1]), cp[2] - pivot[2],
                            r[0] * DEG, r[1] * DEG, r[2] * DEG, List.of(box), List.of()));
                } else {
                    boxes.add(box(uv, origin, size, pivot, inflate, mirror));
                }
            }
        }
        for (String kid : kids.getOrDefault(name, List.of())) {
            children.add(part(kid, pivot, bones, kids, notes, depth + 1));
        }
        float x;
        float y;
        float z;
        if (parentPivot == null) {
            x = pivot[0];
            y = 24 - pivot[1];
            z = pivot[2];
        } else {
            x = pivot[0] - parentPivot[0];
            y = -(pivot[1] - parentPivot[1]);
            z = pivot[2] - parentPivot[2];
        }
        return new Part(name, x, y, z, rot[0] * DEG, rot[1] * DEG, rot[2] * DEG, List.copyOf(boxes), List.copyOf(children));
    }

    /** A Bedrock cube (absolute, Y up, origin at its lowest corner) as a Java box relative to {@code pivot}. */
    private static Box box(int[] uv, float[] origin, float[] size, float[] pivot, float inflate, boolean mirror) {
        return new Box(uv[0], uv[1], origin[0] - pivot[0], pivot[1] - origin[1] - size[1], origin[2] - pivot[2],
                size[0], size[1], size[2], inflate, mirror);
    }

    private static int[] uv(Object uv, String bone, List<String> notes) {
        if (uv instanceof List<?> l && l.size() >= 2) {
            return new int[]{Math.round((float) Json.num(l.get(0), 0)), Math.round((float) Json.num(l.get(1), 0))};
        }
        if (uv instanceof Map<?, ?> faces) {
            notes.add("bone '" + bone + "' uses per-face UV, which Java models can't do; read as box UV from its north face."
                    + " In Blockbench: File > Project > UV Mode: Box UV.");
            Object north = faces.get("north");
            if (north == null && !faces.isEmpty()) {
                north = faces.values().iterator().next();
            }
            return uv(Json.get(north, "uv"), bone, new ArrayList<>());
        }
        return new int[]{0, 0};
    }

    private static float[] vec(Object o) {
        float[] v = new float[3];
        if (o instanceof List<?> l) {
            for (int i = 0; i < 3 && i < l.size(); i++) {
                v[i] = (float) Json.num(l.get(i), 0);
            }
        }
        return v;
    }

    private static boolean isZero(float[] v) {
        return v[0] == 0 && v[1] == 0 && v[2] == 0;
    }

    /** Enough JSON for geometry files: objects, arrays, strings, numbers, booleans, null. */
    static final class Json {
        private final String s;
        private int i;

        private Json(String s) {
            this.s = s;
        }

        static Object parse(String text) {
            Json j = new Json(text);
            j.ws();
            Object v = j.value();
            j.ws();
            if (j.i != j.s.length()) {
                throw j.error("stray text after the end");
            }
            return v;
        }

        static Object get(Object root, String... path) {
            Object o = root;
            for (String p : path) {
                if (!(o instanceof Map<?, ?> m)) {
                    return null;
                }
                o = m.get(p);
            }
            return o;
        }

        static double num(Object o, double fallback) {
            return o instanceof Number n ? n.doubleValue() : fallback;
        }

        private Object value() {
            if (i >= s.length()) {
                throw error("unexpected end");
            }
            char c = s.charAt(i);
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> word("true", Boolean.TRUE);
                case 'f' -> word("false", Boolean.FALSE);
                case 'n' -> word("null", null);
                default -> number();
            };
        }

        private Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++;
            ws();
            if (peek('}')) {
                i++;
                return m;
            }
            while (true) {
                ws();
                String key = string();
                ws();
                expect(':');
                ws();
                m.put(key, value());
                ws();
                if (peek(',')) {
                    i++;
                    continue;
                }
                expect('}');
                return m;
            }
        }

        private List<Object> array() {
            List<Object> l = new ArrayList<>();
            i++;
            ws();
            if (peek(']')) {
                i++;
                return l;
            }
            while (true) {
                ws();
                l.add(value());
                ws();
                if (peek(',')) {
                    i++;
                    continue;
                }
                expect(']');
                return l;
            }
        }

        private String string() {
            expect('"');
            StringBuilder b = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') {
                    return b.toString();
                }
                if (c == '\\') {
                    if (i >= s.length()) {
                        break;
                    }
                    char e = s.charAt(i++);
                    switch (e) {
                        case 'n' -> b.append('\n');
                        case 't' -> b.append('\t');
                        case 'r' -> b.append('\r');
                        case 'b' -> b.append('\b');
                        case 'f' -> b.append('\f');
                        case 'u' -> {
                            if (i + 4 > s.length()) {
                                throw error("broken \\u escape");
                            }
                            b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                        }
                        default -> b.append(e);
                    }
                } else {
                    b.append(c);
                }
            }
            throw error("unterminated string");
        }

        private Object number() {
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
                i++;
            }
            if (start == i) {
                throw error("unexpected '" + s.charAt(i) + "'");
            }
            try {
                return Double.parseDouble(s.substring(start, i));
            } catch (NumberFormatException e) {
                throw error("bad number " + s.substring(start, i));
            }
        }

        private Object word(String w, Object v) {
            if (!s.startsWith(w, i)) {
                throw error("unexpected '" + s.charAt(i) + "'");
            }
            i += w.length();
            return v;
        }

        private void ws() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                    i++;
                } else if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '/') { // Blockbench doesn't, people do
                    while (i < s.length() && s.charAt(i) != '\n') {
                        i++;
                    }
                } else {
                    return;
                }
            }
        }

        private boolean peek(char c) {
            return i < s.length() && s.charAt(i) == c;
        }

        private void expect(char c) {
            if (!peek(c)) {
                throw error("expected '" + c + "'");
            }
            i++;
        }

        private IllegalArgumentException error(String what) {
            int line = 1;
            for (int k = 0; k < Math.min(i, s.length()); k++) {
                if (s.charAt(k) == '\n') {
                    line++;
                }
            }
            return new IllegalArgumentException("JSON, line " + line + ": " + what);
        }
    }
}
