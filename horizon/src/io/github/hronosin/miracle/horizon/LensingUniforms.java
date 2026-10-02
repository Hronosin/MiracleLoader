package io.github.hronosin.miracle.horizon;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;

import java.io.Reader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.function.Supplier;

/**
 * Lensing's uniforms, at work: before a post effect's pass is drawn, its uniform blocks that have
 * values from code get a fresh buffer when those values changed. The game made the original ones
 * read-only, so they're replaced rather than written. The GPU device and buffers are reached by
 * name: blaze3d's (to 26.2) and renderpearl's (26.3) keep their names in every version.
 */
final class LensingUniforms {

    private LensingUniforms() {
    }

    /** One pass of one effect, as seen the first time it's drawn. */
    private static final class Pass {
        String effect;
        List<Block> blocks = List.of();
        Map<Object, Object> buffers;
    }

    private record Block(String name, List<Lensing.Field> fields, byte[][] last) {
    }

    private static final Map<Object, Pass> PASSES = new WeakHashMap<>();
    private static Field nameField;
    private static Field mapField;
    private static Method createBuffer;
    private static Object device;
    private static boolean broken;

    /** The hook: before {@code PostPass#addToFrame}. Never throws. */
    static void beforeFrame(Object postPass) {
        if (Lensing.UNIFORMS.isEmpty() || broken) {
            return;
        }
        try {
            Pass p;
            synchronized (PASSES) {
                p = PASSES.computeIfAbsent(postPass, LensingUniforms::look);
            }
            Map<String, Supplier<float[]>> mine = p.effect == null ? null : Lensing.UNIFORMS.get(p.effect);
            if (mine == null) {
                return;
            }
            for (Block b : p.blocks) {
                if (b.fields.stream().noneMatch(f -> mine.containsKey(f.name()))) {
                    continue;
                }
                ByteBuffer bytes = Lensing.std140(b.fields, name -> {
                    Supplier<float[]> s = mine.get(name);
                    return s == null ? null : s.get();
                });
                byte[] now = new byte[bytes.capacity()];
                bytes.get(0, now);
                if (Arrays.equals(now, b.last[0])) {
                    continue;
                }
                b.last[0] = now;
                Object fresh = createBuffer.invoke(device, (Supplier<String>) () -> "Event Horizon " + p.effect + " " + b.name, 128, bytes);
                Object old = p.buffers.put(b.name, fresh);
                if (old instanceof AutoCloseable c) {
                    c.close();
                }
            }
        } catch (Exception | LinkageError e) {
            broken = true;
            EventHorizon.LOG.accept("Lensing: uniforms from code stopped working here (" + e + "); the JSON's values stay.");
        }
    }

    private static Pass look(Object postPass) {
        Pass p = new Pass();
        try {
            if (nameField == null) {
                for (Field f : postPass.getClass().getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) {
                        continue;
                    }
                    if (f.getType() == String.class && nameField == null) {
                        f.setAccessible(true);
                        nameField = f;
                    } else if (f.getType() == Map.class && mapField == null) {
                        f.setAccessible(true);
                        mapField = f;
                    }
                }
                Class<?> rs = Class.forName("com.mojang.blaze3d.systems.RenderSystem", true, postPass.getClass().getClassLoader());
                device = rs.getMethod("getDevice").invoke(null);
                createBuffer = publicMethod(device.getClass(), "createBuffer", Supplier.class, int.class, ByteBuffer.class);
            }
            String name = (String) nameField.get(postPass);          // "mymod:warp/0"
            int slash = name.lastIndexOf('/');
            p.effect = name.substring(0, slash);
            int index = Integer.parseInt(name.substring(slash + 1));
            if (!Lensing.UNIFORMS.containsKey(p.effect)) {
                return p;
            }
            @SuppressWarnings("unchecked")
            Map<Object, Object> buffers = (Map<Object, Object>) mapField.get(postPass);
            p.buffers = buffers;
            p.blocks = blocks(p.effect, index);
        } catch (Exception | LinkageError e) {
            p.effect = null;
            EventHorizon.LOG.accept("Lensing: can't give uniforms to a pass (" + e + ").");
        }
        return p;
    }

    /** The uniform blocks of one pass, from the effect's JSON. */
    private static List<Block> blocks(String effect, int index) throws Exception {
        Identifier id = Identifier.parse(effect);
        Optional<Resource> json = Minecraft.getInstance().getResourceManager()
                .getResource(Identifier.fromNamespaceAndPath(id.getNamespace(), "post_effect/" + id.getPath() + ".json"));
        if (json.isEmpty()) {
            return List.of();
        }
        JsonObject root;
        try (Reader r = json.get().openAsReader()) {
            root = JsonParser.parseReader(r).getAsJsonObject();
        }
        JsonArray passes = root.getAsJsonArray("passes");
        if (passes == null || index >= passes.size()) {
            return List.of();
        }
        JsonObject uniforms = passes.get(index).getAsJsonObject().getAsJsonObject("uniforms");
        if (uniforms == null) {
            return List.of();
        }
        List<Block> out = new ArrayList<>();
        Map<String, JsonElement> sorted = new LinkedHashMap<>();
        uniforms.entrySet().forEach(e -> sorted.put(e.getKey(), e.getValue()));
        for (var e : sorted.entrySet()) {
            List<Lensing.Field> fields = new ArrayList<>();
            for (JsonElement f : e.getValue().getAsJsonArray()) {
                JsonObject o = f.getAsJsonObject();
                JsonElement v = o.get("value");
                float[] value;
                if (v == null) {
                    value = new float[0];
                } else if (v.isJsonArray()) {
                    JsonArray a = v.getAsJsonArray();
                    value = new float[a.size()];
                    for (int i = 0; i < a.size(); i++) {
                        value[i] = a.get(i).getAsFloat();
                    }
                } else {
                    value = new float[] {v.getAsFloat()};
                }
                fields.add(new Lensing.Field(o.get("name").getAsString(), o.get("type").getAsString(), value));
            }
            out.add(new Block(e.getKey(), fields, new byte[1][]));
        }
        return out;
    }

    /** A public method, looked up on a public type the object is (an interface or a superclass). */
    private static Method publicMethod(Class<?> c, String name, Class<?>... params) throws NoSuchMethodException {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            if (Modifier.isPublic(k.getModifiers())) {
                try {
                    return k.getMethod(name, params);
                } catch (NoSuchMethodException ignored) {
                    // try further up
                }
            }
            for (Class<?> i : k.getInterfaces()) {
                if (Modifier.isPublic(i.getModifiers())) {
                    try {
                        return i.getMethod(name, params);
                    } catch (NoSuchMethodException ignored) {
                        // try the next
                    }
                }
            }
        }
        throw new NoSuchMethodException(c.getName() + "." + name);
    }
}
