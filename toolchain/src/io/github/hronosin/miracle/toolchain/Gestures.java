package io.github.hronosin.miracle.toolchain;

import io.github.hronosin.miracle.Log;
import io.github.hronosin.miracle.api.Mods;
import io.github.hronosin.miracle.rgct.Rgct;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import net.minecraft.resources.Identifier;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Gestures: keys players can press, listed in Options → Controls under your mod's name, and
 * rebindable there like any vanilla key. Known as {@link Keybinds} to the unceremonious.
 *
 * <pre>{@code
 * public void onLaunch() {
 *     Gestures.key("pray", "G", () -> PRAYER.toServer(new Scroll()));   // G by default; players rebind it
 * }
 * }</pre>
 *
 * <p>The action runs on the client thread, once per press, while no screen is open. A gesture
 * is a client thing: on a dedicated server the same calls do nothing, so one jar serves both.
 * Declare gestures in {@code onLaunch()}: the game reads its key settings once, early, and
 * they have to be there by then.
 *
 * <p>The Controls screen shows translated names: {@code key.<namespace>.<name>} for each gesture
 * and {@code key.category.<namespace>.keys} for the group (the namespace is your mod id with
 * {@code -} as {@code _}). Put them in {@code assets/<namespace>/lang/en_us.json} and call
 * {@link Scripture#reveal()}; without them the
 * screen shows the raw keys, which works but looks like a debug build.
 */
public class Gestures {

    static final String KEY = "gestures";

    /** One declared key. */
    public static final class Gesture {
        final String modId;
        final String name;
        final String key;
        final Runnable action;
        volatile Object mapping; // a KeyMapping, once the game has one

        Gesture(String modId, String name, String key, Runnable action) {
            this.modId = modId;
            this.name = name;
            this.key = key;
            this.action = action;
        }

        /** {@code key.<mod id>.<name>}: the translation key, and the name in options.txt. */
        public String id() {
            return "key." + modId + "." + name;
        }

        /** Held down right now. Always false on a dedicated server. */
        public boolean isDown() {
            return mapping != null && Hands.isDown(mapping);
        }

        @Override
        public String toString() {
            return id();
        }
    }

    private static final List<Gesture> GESTURES = new ArrayList<>();
    private static volatile boolean sealed;

    protected Gestures() {
    }

    /**
     * A key, by name: {@code "G"}, {@code "7"}, {@code "F6"}, {@code "SPACE"}, {@code "LEFT_ALT"},
     * {@code "KP_5"}, {@code "UP"}... or {@code "NONE"} for unbound until the player picks one.
     * The game's own names work too ({@code "key.keyboard.g"}, {@code "key.mouse.middle"}).
     *
     * <p>Names, not numbers: the game moved from GLFW to SDL in 26.x and the key numbers changed
     * meaning; the names (the ones in options.txt) did not.
     */
    public static synchronized Gesture key(String name, String defaultKey, Runnable action) {
        Mods.Mod mod = Faithful.check("Gestures.key", KEY);
        if (!name.matches("[a-z0-9_.-]+")) {
            throw new IllegalArgumentException("'" + name + "': gesture names are lowercase letters, digits and _ . -");
        }
        String key = keyName(defaultKey);
        if (sealed) {
            throw new IllegalStateException(mod.id() + " declares the gesture '" + name + "' after the game read its key"
                    + " settings. Declare gestures in onLaunch().");
        }
        String ns = Creation.namespace(mod.id());
        for (Gesture g : GESTURES) {
            if (g.modId.equals(ns) && g.name.equals(name)) {
                throw new IllegalArgumentException(g.id() + " was already declared");
            }
        }
        Gesture g = new Gesture(ns, name, key, action);
        GESTURES.add(g);
        return g;
    }

    /** A friendly key name as the game writes it: "LEFT_ALT" is "key.keyboard.left.alt". */
    static String keyName(String key) {
        String k = key.strip();
        if (k.startsWith("key.keyboard.") || k.startsWith("key.mouse.")) {
            return k;
        }
        k = k.toUpperCase(Locale.ROOT).replace(' ', '_');
        String suffix;
        if (k.matches("[A-Z0-9]") || k.matches("F([1-9]|1[0-9]|2[0-4])")) {
            suffix = k.toLowerCase(Locale.ROOT);
        } else if (k.matches("KP_[0-9]")) {
            suffix = "keypad." + k.charAt(3);
        } else if (NAMED.containsKey(k)) {
            suffix = NAMED.get(k);
        } else {
            throw new IllegalArgumentException("'" + key + "' isn't a key this library knows. Try a letter, a digit, F1-F24,"
                    + " KP_0-KP_9, one of " + new java.util.TreeSet<>(NAMED.keySet()) + ", or the game's own name"
                    + " (key.keyboard.x).");
        }
        return "key.keyboard." + suffix;
    }

    private static final Map<String, String> NAMED = Map.ofEntries(
            Map.entry("NONE", "unknown"), Map.entry("SPACE", "space"), Map.entry("APOSTROPHE", "apostrophe"),
            Map.entry("COMMA", "comma"), Map.entry("MINUS", "minus"), Map.entry("PERIOD", "period"),
            Map.entry("SLASH", "slash"), Map.entry("SEMICOLON", "semicolon"), Map.entry("EQUAL", "equal"),
            Map.entry("LEFT_BRACKET", "left.bracket"), Map.entry("BACKSLASH", "backslash"),
            Map.entry("RIGHT_BRACKET", "right.bracket"), Map.entry("GRAVE", "grave.accent"), Map.entry("ENTER", "enter"),
            Map.entry("TAB", "tab"), Map.entry("BACKSPACE", "backspace"), Map.entry("INSERT", "insert"),
            Map.entry("DELETE", "delete"), Map.entry("RIGHT", "right"), Map.entry("LEFT", "left"),
            Map.entry("DOWN", "down"), Map.entry("UP", "up"), Map.entry("PAGE_UP", "page.up"),
            Map.entry("PAGE_DOWN", "page.down"), Map.entry("HOME", "home"), Map.entry("END", "end"),
            Map.entry("CAPS_LOCK", "caps.lock"), Map.entry("LEFT_SHIFT", "left.shift"),
            Map.entry("LEFT_CONTROL", "left.control"), Map.entry("LEFT_ALT", "left.alt"),
            Map.entry("RIGHT_SHIFT", "right.shift"), Map.entry("RIGHT_CONTROL", "right.control"),
            Map.entry("RIGHT_ALT", "right.alt"));

    // --- installation (startup, client only, in the library's name) ------------------------------

    static void install(Rgct rgct) {
        // Options.load() reads options.txt into keyMappings: ours must be in the array first.
        rgct.target("net.minecraft.client.Options")
                .method("load", "()V")
                .atHead(self -> Hands.inject(self));
        rgct.target("net.minecraft.client.Minecraft")
                .method("tick", "()V")
                .atReturn(self -> Hands.tick());
    }

    /** The game-facing half, loaded only on a client, once the game is running. */
    private static final class Hands {

        private Hands() {
        }

        static void inject(Object self) {
            Options options = (Options) self;
            List<Gesture> ours;
            synchronized (Gestures.class) {
                sealed = true;
                ours = List.copyOf(GESTURES);
            }
            if (ours.isEmpty()) {
                return;
            }
            try {
                Field field = keyMappingsField(options);
                KeyMapping[] vanilla = (KeyMapping[]) field.get(options);
                List<KeyMapping> all = new ArrayList<>(List.of(vanilla));
                for (Gesture g : ours) {
                    if (g.mapping == null) {
                        KeyMapping.Category category = category(g.modId);
                        InputConstants.Key key = key(g);
                        g.mapping = new KeyMapping(g.id(), key.getType(), key.getValue(), category);
                    }
                    if (!all.contains((KeyMapping) g.mapping)) {
                        all.add((KeyMapping) g.mapping);
                    }
                }
                field.set(options, all.toArray(KeyMapping[]::new));
                Log.info("MiracleToolChain: " + ours.size() + " gesture(s) added to the controls.");
            } catch (ReflectiveOperationException | RuntimeException e) {
                Log.warn("MiracleToolChain: couldn't add gestures to the controls: " + e);
            }
        }

        /** One "<mod id>:keys" category per mod, registered with the game the first time. */
        private static KeyMapping.Category category(String modId) {
            return CATEGORIES.computeIfAbsent(modId,
                    m -> KeyMapping.Category.register(Identifier.fromNamespaceAndPath(m, "keys")));
        }

        private static final Map<String, KeyMapping.Category> CATEGORIES = new java.util.HashMap<>();

        /**
         * Options has several KeyMapping arrays (hotbar slots, debug keys...) and one with all of
         * them, which the controls screen and options.txt use: the longest. Found by type and
         * size, so obfuscated names don't matter.
         */
        private static Field keyMappingsField(Options options) throws ReflectiveOperationException {
            Field best = null;
            int bestLength = -1;
            for (Field f : Options.class.getDeclaredFields()) {
                if (f.getType() == KeyMapping[].class && !java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    f.setAccessible(true);
                    KeyMapping[] a = (KeyMapping[]) f.get(options);
                    if (a != null && a.length > bestLength) {
                        best = f;
                        bestLength = a.length;
                    }
                }
            }
            if (best == null) {
                throw new NoSuchFieldException("Options has no KeyMapping[] in this version");
            }
            return best;
        }

        private static InputConstants.Key key(Gesture g) {
            try {
                return InputConstants.getKey(g.key);
            } catch (RuntimeException e) {
                Log.warn("MiracleToolChain: " + g + " wants " + g.key + ", which this game doesn't know; it starts unbound.");
                return InputConstants.UNKNOWN;
            }
        }

        static void tick() {
            for (Gesture g : GESTURES) {
                KeyMapping m = (KeyMapping) g.mapping;
                if (m == null) {
                    continue;
                }
                while (m.consumeClick()) {
                    g.action.run();
                }
            }
        }

        static boolean isDown(Object mapping) {
            return ((KeyMapping) mapping).isDown();
        }
    }
}
