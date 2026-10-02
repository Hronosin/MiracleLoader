package io.github.hronosin.miracle.horizon;

import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** What Lensing's hooks in the game's shader loading do, apart so nothing loads a game class early. */
final class LensingHooks {

    private static final Set<String> SAID = ConcurrentHashMap.newKeySet();

    private LensingHooks() {
    }

    /** A shader file translated for the running game, or null to leave it as it is. */
    static Object shader(Object location, Object resource) {
        try {
            if (!(location instanceof Identifier id) || !(resource instanceof Resource r) || id.getNamespace().equals("minecraft")) {
                return null;
            }
            Lensing.Stage stage = Lensing.Stage.of(id.getPath());
            Lensing.Dialect to = Lensing.dialect();
            if (stage == null || to == null) {
                return null;
            }
            String source;
            try (InputStream in = r.open()) {
                source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            String out = Lensing.translate(source, to, stage);
            if (out.equals(source)) {
                return null;
            }
            if (SAID.add(id.toString())) {
                EventHorizon.LOG.accept("Lensing: " + id + " translated to the " + to.name().toLowerCase(java.util.Locale.ROOT)
                        + " dialect.");
            }
            byte[] bytes = out.getBytes(StandardCharsets.UTF_8);
            return new Resource(r.source(), () -> new ByteArrayInputStream(bytes));
        } catch (IOException | RuntimeException e) {
            EventHorizon.LOG.accept("Lensing: left " + location + " as it was: " + e);
            return null;
        }
    }

    private static final Map<Object, Object> WRAPPED = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** Up to 26.2: the includes a shader may import, translated; null if none need it. */
    static Object includes(Object map) {
        if (!(map instanceof Map<?, ?> m) || m.isEmpty()) {
            return null;
        }
        Object done = WRAPPED.get(map);
        if (done != null) {
            return done == map ? null : done;
        }
        Map<Object, Object> out = new LinkedHashMap<>();
        boolean changed = false;
        for (Map.Entry<?, ?> e : m.entrySet()) {
            Object t = shader(e.getKey(), e.getValue());
            out.put(e.getKey(), t != null ? t : e.getValue());
            changed |= t != null;
        }
        WRAPPED.put(map, changed ? out : map);
        return changed ? out : null;
    }
}
