package io.github.hronosin.miracle.toolchain;

import io.github.hronosin.miracle.Log;
import io.github.hronosin.miracle.api.Mods;
import io.github.hronosin.miracle.rgct.Rgct;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ConfigurationTask;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Communion: before a player joins, the server and the client compare their mods, and a
 * mismatch ends with a message saying exactly what's wrong, instead of a client that crashes
 * on the first unknown block or a key that silently does nothing. Known as {@link Handshake} to
 * the secular.
 *
 * <p>It runs by itself. Which mods have to be on both sides is worked out at startup:
 * <ul>
 *   <li>a mod that creates things ({@link Creation}) or talks over {@link Telepathy} is
 *       <b>bound</b>: it must be on both sides, in the same version, and so must this library;</li>
 *   <li>everything else (omens, blessings, commands, keys) works on one side alone, and is
 *       nobody else's business.</li>
 * </ul>
 * A mod can say otherwise from {@code onLaunch()}: {@link #bothSides()} binds it (a server-side
 * mod whose client half is needed), {@link #eitherSide()} unbinds a Telepathy mod that copes with
 * silence on the other end. Creating things always binds: numbering depends on it.
 *
 * <p>What the player sees when it fails, on their disconnect screen:
 * <pre>
 * Communion refused. Your mods and the server's don't match:
 *   Missing: hallelujah 0.3.0
 *   Different version: holy-hops (yours 1.0, the server's 1.2)
 *   The server doesn't have: chaos 0.1 (remove it to join)
 * </pre>
 * A game without this library (vanilla, or a server that never heard of Miracle) is noticed
 * too: a server with bound mods turns it away after {@code communion_timeout} seconds with the
 * list of what to install, and a client with bound mods leaves a server that never offered
 * communion. Settings: {@code communion} and {@code communion_timeout} in
 * {@code config/miracle-toolchain.toml}.
 */
public class Communion {

    static final int MAGIC = 0x4D434D31; // "MCM1"

    /** Mods that must be on both sides, worked out at startup, amended from onLaunch(). */
    private static final Set<String> BOUND = ConcurrentHashMap.newKeySet();
    /** Mods that asked for eitherSide(): Telepathy alone doesn't bind them. */
    private static final Set<String> LOOSE = ConcurrentHashMap.newKeySet();
    /** Mods that create things: bound, whatever they say. */
    private static final Set<String> CREATORS = ConcurrentHashMap.newKeySet();

    static volatile boolean enabled = true;
    static volatile int timeoutSeconds = 10;

    protected Communion() {
    }

    /** Your mod must be on both sides, in the same version, even though nothing here says so. */
    public static void bothSides() {
        Mods.Mod mod = Faithful.caller("Communion.bothSides");
        LOOSE.remove(mod.id());
        BOUND.add(mod.id());
    }

    /**
     * Your mod may be missing on the other side: its Telepathy messages simply go unanswered there.
     * Doesn't apply to mods that create things; those are bound regardless.
     */
    public static void eitherSide() {
        Mods.Mod mod = Faithful.caller("Communion.eitherSide");
        if (CREATORS.contains(mod.id())) {
            Log.warn("MiracleToolChain: " + mod.id() + " asks for eitherSide(), but it creates things: blocks and items"
                    + " travel as numbers, and both sides have to count the same. It stays bound.");
            return;
        }
        LOOSE.add(mod.id());
        BOUND.remove(mod.id());
    }

    /**
     * The mods a player's game has, id to version, as it told the server when joining. Empty for a
     * game without this library (vanilla, for one): it never said.
     */
    public static Optional<Map<String, String>> modsOf(ServerPlayer player) {
        return Optional.ofNullable(Altar.KNOWN.get(player.getUUID()));
    }

    /** True if the player's game told the server it has this mod. */
    public static boolean has(ServerPlayer player, String modId) {
        return modsOf(player).map(m -> m.containsKey(modId)).orElse(false);
    }

    // --- the startup part --------------------------------------------------------------------------

    /** At startup, from the Prophecy: this mod creates things or talks over Telepathy. */
    static void bind(String modId, boolean creates) {
        BOUND.add(modId);
        if (creates) {
            CREATORS.add(modId);
        }
    }

    static boolean isBound(String modId) {
        if (LOOSE.contains(modId) && !CREATORS.contains(modId)) {
            return false;
        }
        if (BOUND.contains(modId)) {
            return true;
        }
        // The library is bound whenever anything else is: it carries the wire and the numbering.
        return modId.equals(MiracleToolChain.ID) && BOUND.stream().anyMatch(m -> !LOOSE.contains(m) || CREATORS.contains(m));
    }

    static void install(Rgct rgct, boolean client) {
        rgct.target("net.minecraft.network.protocol.common.custom.CustomPacketPayload$1")
                .method("findCodec", "(Lnet/minecraft/resources/Identifier;)Lnet/minecraft/network/codec/StreamCodec;")
                .interceptHead(ctx -> {
                    if (Altar.isOurs(ctx.arg(0))) {
                        ctx.cancel(Altar.codec());
                    }
                });
        // The server offers communion as one of its configuration tasks, before the player is in the world.
        rgct.target("net.minecraft.server.network.ServerConfigurationPacketListenerImpl")
                .method("addOptionalTasks", "()V")
                .atReturn(self -> Altar.offer(self));
        rgct.target("net.minecraft.server.network.ServerCommonPacketListenerImpl")
                .method("handleCustomPayload", "(Lnet/minecraft/network/protocol/common/ServerboundCustomPayloadPacket;)V")
                .interceptHead(ctx -> {
                    if (Altar.answered(ctx.self(), ctx.arg(0))) {
                        ctx.cancel();
                    }
                });
        if (client) {
            rgct.target("net.minecraft.client.multiplayer.ClientConfigurationPacketListenerImpl")
                    .method("handleCustomPayload", "(Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;)V")
                    .interceptHead(ctx -> {
                        if (Pew.offered(ctx.self(), ctx.arg(0))) {
                            ctx.cancel();
                        }
                    });
            rgct.target("net.minecraft.client.multiplayer.ClientConfigurationPacketListenerImpl")
                    .method("handleConfigurationFinished",
                            "(Lnet/minecraft/network/protocol/configuration/ClientboundFinishConfigurationPacket;)V")
                    .interceptHead(ctx -> {
                        if (Pew.refuseUnoffered(ctx.self())) {
                            ctx.cancel();
                        }
                    });
        }
    }

    // --- the manifest, and the judgement (no game classes: SelfTest checks these) ------------------

    /** One side's mods, as it tells the other. */
    record Manifest(String library, List<Entry> mods, long creationHash, int creations) {

        /** {@code since}: the oldest version of this mod its other side may have ({@code communes_since}), or null. */
        record Entry(String id, String version, boolean bound, String since) {
            Entry(String id, String version, boolean bound) {
                this(id, version, bound, null);
            }

            @Override
            public String toString() {
                return id + " " + version;
            }
        }

        /** This side's manifest. */
        static Manifest ours() {
            List<Entry> mods = new ArrayList<>();
            for (Mods.Mod m : Mods.all()) {
                mods.add(new Entry(m.id(), m.version(), isBound(m.id()), since(m)));
            }
            List<String> made = Creation.inventory();
            return new Manifest(Mods.get(MiracleToolChain.ID).map(Mods.Mod::version).orElse("?"), mods,
                    fingerprint(made), made.size());
        }

        byte[] bytes() {
            Scroll s = new Scroll().writeInt(MAGIC).writeString(library).writeInt(mods.size());
            for (Entry e : mods) {
                s.writeString(e.id()).writeString(e.version()).writeBoolean(e.bound());
            }
            s.writeLong(creationHash).writeInt(creations);
            // Since 1.6: which mods play with older versions of themselves. Older libraries stop
            // reading before this, so it costs them nothing.
            List<Entry> since = mods.stream().filter(e -> e.since() != null).toList();
            s.writeInt(since.size());
            since.forEach(e -> s.writeString(e.id()).writeString(e.since()));
            return s.bytes();
        }

        static Manifest read(byte[] data) {
            Scroll s = Scroll.read(data);
            if (s.readInt() != MAGIC) {
                throw new IllegalArgumentException("not a communion manifest");
            }
            String library = s.readString();
            int n = s.readInt();
            if (n < 0 || n > 10_000) {
                throw new IllegalArgumentException(n + " mods? No.");
            }
            List<Entry> mods = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                mods.add(new Entry(s.readString(), s.readString(), s.readBoolean()));
            }
            long hash = s.readLong();
            int creations = s.readInt();
            if (s.hasMore()) {                  // a 1.6 library or newer: the communes_since of some mods
                int k = s.readInt();
                if (k < 0 || k > n) {
                    throw new IllegalArgumentException(k + " ranges for " + n + " mods? No.");
                }
                Map<String, String> since = new LinkedHashMap<>();
                for (int i = 0; i < k; i++) {
                    since.put(s.readString(), s.readString());
                }
                mods.replaceAll(e -> since.containsKey(e.id()) ? new Entry(e.id(), e.version(), e.bound(), since.get(e.id())) : e);
            }
            return new Manifest(library, mods, hash, creations);
        }

        Map<String, Entry> byId() {
            Map<String, Entry> m = new LinkedHashMap<>();
            mods.forEach(e -> m.put(e.id(), e));
            return m;
        }

        List<Entry> bound() {
            return mods.stream().filter(Entry::bound).toList();
        }
    }

    // --- versions that play together ------------------------------------------------------------------

    private static final Map<String, String> SINCE = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * A mod's {@code communes_since} from its {@code miracle.mod.toml}: the oldest version of itself
     * its other side may have. Null if it has none (then only its own version will do), or if it's
     * newer than the mod itself (said once in the log).
     */
    static String since(Mods.Mod m) {
        String v = SINCE.computeIfAbsent(m.id(), id -> {
            try (var jf = new java.util.jar.JarFile(m.jar().toFile())) {
                var e = jf.getJarEntry("miracle.mod.toml");
                if (e == null) {
                    return "";
                }
                String text;
                try (var in = jf.getInputStream(e)) {
                    text = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                }
                if (!(io.github.hronosin.miracle.MiniToml.parse(text).get("communes_since") instanceof String since)
                        || since.isBlank()) {
                    return "";
                }
                if (compareVersions(since.strip(), m.version()) > 0) {
                    Log.warn("MiracleToolChain: " + id + " says communes_since = \"" + since + "\", newer than itself ("
                            + m.version() + "). Ignored: only " + m.version() + " will do.");
                    return "";
                }
                return since.strip();
            } catch (Exception ex) {
                return "";
            }
        });
        return v.isEmpty() ? null : v;
    }

    /**
     * Whether two versions of one mod play together: the same version, or one of them says, with
     * {@code communes_since}, that the other (older or the same) is new enough. The newer side
     * knows what it still understands, so it decides; both sides work it out the same way.
     */
    static boolean communes(Manifest.Entry a, Manifest.Entry b) {
        return a.version().equals(b.version()) || accepts(a, b) || accepts(b, a);
    }

    private static boolean accepts(Manifest.Entry newer, Manifest.Entry older) {
        return newer.since() != null && compareVersions(older.version(), newer.version()) <= 0
                && compareVersions(newer.since(), older.version()) <= 0;
    }

    private static String differentVersion(Manifest.Entry server, Manifest.Entry client) {
        boolean serverNewer = compareVersions(server.version(), client.version()) >= 0;
        Manifest.Entry newer = serverNewer ? server : client;
        String range = newer.since() == null ? "" : ", which plays with " + newer.since() + " and newer";
        return "Different version: " + server.id() + " (yours " + client.version() + (serverNewer ? "" : range)
                + ", the server's " + server.version() + (serverNewer ? range : "") + ")";
    }

    /** 1.10 > 1.9; missing parts count as 0; anything after - or + is ignored (as the loader compares). */
    static int compareVersions(String a, String b) {
        String[] x = a.split("[-+]", 2)[0].split("\\.");
        String[] y = b.split("[-+]", 2)[0].split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            long p = i < x.length ? number(x[i]) : 0;
            long q = i < y.length ? number(y[i]) : 0;
            if (p != q) {
                return Long.compare(p, q);
            }
        }
        return 0;
    }

    private static long number(String s) {
        try {
            return Long.parseLong(s.strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** FNV-1a over every created id, in order: same things, same numbers, same hash. */
    static long fingerprint(List<String> ids) {
        long h = 0xCBF29CE484222325L;
        for (String id : ids) {
            for (byte b : (id + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
                h ^= b & 0xFF;
                h *= 0x100000001B3L;
            }
        }
        return h;
    }

    /**
     * What's wrong between the server's mods and a joining client's, in the player's words.
     * Empty when they can play together.
     */
    static List<String> judge(Manifest server, Manifest client) {
        List<String> wrong = new ArrayList<>();
        Map<String, Manifest.Entry> theirs = client.byId();
        Map<String, Manifest.Entry> ours = server.byId();
        for (Manifest.Entry s : server.bound()) {
            Manifest.Entry c = theirs.get(s.id());
            if (c == null) {
                wrong.add("Missing: " + s);
            } else if (!communes(s, c)) {
                wrong.add(differentVersion(s, c));
            }
        }
        for (Manifest.Entry c : client.bound()) {
            Manifest.Entry s = ours.get(c.id());
            if (s == null) {
                wrong.add("The server doesn't have: " + c + " (remove it to join)");
            } else if (!s.bound() && !communes(s, c)) {
                wrong.add(differentVersion(s, c));
            }
        }
        if (wrong.isEmpty() && (server.creationHash() != client.creationHash() || server.creations() != client.creations())) {
            wrong.add("Same mods, different creations: " + client.creations() + " new things in your game, "
                    + server.creations() + " on the server. A setting that turns content on or off?");
        }
        return wrong;
    }

    static String refusal(List<String> wrong) {
        return "Communion refused. Your mods and the server's don't match:\n  " + String.join("\n  ", wrong)
                + "\n\nNo miracle today.";
    }

    /** For a game that never answered: most likely vanilla, or without this library. */
    static String strangerRefusal(Manifest server) {
        return "This server runs MiracleLoader, and its mods have to be in your game too:\n  "
                + String.join("\n  ", server.bound().stream().map(Manifest.Entry::toString).toList())
                + "\n\nInstall MiracleLoader and these, then try again.";
    }

    /** For a client whose bound mods meet a server that never offered communion. */
    static String heathenRefusal(Manifest client) {
        return "This server doesn't offer communion (no MiracleToolChain there, or it's switched off),"
                + " but these mods of yours need the same on both sides:\n  "
                + String.join("\n  ", client.bound().stream().map(Manifest.Entry::toString).toList())
                + "\n\nRemove them to join, or find a server that has them.";
    }

    // --- the server side (game classes; loaded once the game runs) ----------------------------------

    /** Where communion is offered: the server's half. */
    private static final class Altar {

        private static final Identifier ID = Identifier.fromNamespaceAndPath("miracle", "communion");
        private static final CustomPacketPayload.Type<Wafer> TYPE = new CustomPacketPayload.Type<>(ID);
        private static final StreamCodec<FriendlyByteBuf, Wafer> CODEC = new StreamCodec<>() {
            @Override
            public Wafer decode(FriendlyByteBuf buf) {
                int n = buf.readableBytes();
                if (n > 1_000_000) {
                    throw new IllegalArgumentException("a communion manifest of " + n + " bytes? Nobody has that many mods.");
                }
                byte[] data = new byte[n];
                buf.readBytes(data);
                return new Wafer(data);
            }

            @Override
            public void encode(FriendlyByteBuf buf, Wafer w) {
                buf.writeBytes(w.data());
            }
        };
        private static final ConfigurationTask.Type TASK = new ConfigurationTask.Type("miracle:communion");

        /** Answers that arrived, by the listener they arrived on (netty thread to server thread). */
        private static final Map<Object, byte[]> ANSWERS = Collections.synchronizedMap(new WeakHashMap<>());
        /** What each player's game said it has, for modsOf(). */
        static final Map<UUID, Map<String, String>> KNOWN = new ConcurrentHashMap<>();

        private Altar() {
        }

        /** The payload: a manifest, as bytes. */
        record Wafer(byte[] data) implements CustomPacketPayload {
            @Override
            public Type<? extends CustomPacketPayload> type() {
                return TYPE;
            }
        }

        static boolean isOurs(Object id) {
            return ID.equals(id);
        }

        static Object codec() {
            return CODEC;
        }

        static Packet<?> toClient(Manifest m) {
            return new ClientboundCustomPayloadPacket(new Wafer(m.bytes()));
        }

        static Packet<?> toServer(Manifest m) {
            return new ServerboundCustomPayloadPacket(new Wafer(m.bytes()));
        }

        static Manifest unwrap(Object payload) {
            return payload instanceof Wafer w ? Manifest.read(w.data()) : null;
        }

        @SuppressWarnings("unchecked")
        static void offer(Object self) {
            if (!enabled) {
                return;
            }
            try {
                Field queue = field(ServerConfigurationPacketListenerImpl.class, Queue.class);
                ((Queue<ConfigurationTask>) queue.get(self)).add(new Rite((ServerConfigurationPacketListenerImpl) self));
            } catch (ReflectiveOperationException | RuntimeException e) {
                Log.warn("MiracleToolChain: couldn't offer communion to a joining player, letting them in unchecked: " + e);
            }
        }

        static boolean answered(Object self, Object packet) {
            if (!(((ServerboundCustomPayloadPacket) packet).payload() instanceof Wafer w)) {
                return false;
            }
            if (self instanceof ServerConfigurationPacketListenerImpl) {
                ANSWERS.put(self, w.data());
                // Remembered even when nobody waits for the answer, for modsOf(); judged here too, so
                // the log says why even when the client leaves on its own before the rite looks.
                ServerCommonPacketListenerImpl listener = (ServerCommonPacketListenerImpl) self;
                try {
                    Manifest theirs = Manifest.read(w.data());
                    Map<String, String> mods = new LinkedHashMap<>();
                    theirs.mods().forEach(e -> mods.put(e.id(), e.version()));
                    UUID id = uuid(listener);
                    if (id != null) {
                        KNOWN.put(id, Collections.unmodifiableMap(mods));
                    }
                    List<String> wrong = judge(Manifest.ours(), theirs);
                    if (wrong.isEmpty()) {
                        Log.info("Communion: " + name(listener) + " shares our faith (" + theirs.mods().size() + " mod(s)).");
                    } else {
                        Log.warn("Communion: " + name(listener) + " refused: " + String.join("; ", wrong) + ".");
                    }
                } catch (RuntimeException ignored) {
                    // the rite refuses it, if one waits for it
                }
            }
            return true; // in the game phase it's late and harmless: ignored
        }

        /** The configuration task: offer, wait for the answer, judge. */
        static final class Rite implements ConfigurationTask {
            private final ServerConfigurationPacketListenerImpl listener;
            private final Manifest ours = Manifest.ours();
            private final boolean mustAnswer = !ours.bound().isEmpty();
            private long deadline;
            private boolean done;

            Rite(ServerConfigurationPacketListenerImpl listener) {
                this.listener = listener;
            }

            @Override
            public void start(Consumer<Packet<?>> send) {
                send.accept(toClient(ours));
                deadline = System.nanoTime() + timeoutSeconds * 1_000_000_000L;
            }

            @Override
            public boolean tick() {
                if (done) {
                    return false;
                }
                byte[] answer = ANSWERS.remove(listener);
                String who = name(listener);
                if (answer != null) {
                    Manifest theirs;
                    try {
                        theirs = Manifest.read(answer);
                    } catch (RuntimeException e) {
                        return refuse(who + " answered communion with nonsense (" + e.getMessage() + ")",
                                "Your game's answer to communion made no sense. Is something tampering with it?");
                    }
                    List<String> wrong = judge(ours, theirs);
                    if (!wrong.isEmpty()) {
                        return refuse(null, refusal(wrong)); // already logged when it arrived
                    }
                    done = true;
                    return true;
                }
                if (!mustAnswer) {
                    done = true; // nothing here needs the client's mods; whoever it is, it may join
                    return true;
                }
                if (System.nanoTime() > deadline) {
                    return refuse(who + " never answered communion (vanilla, or without MiracleToolChain)",
                            strangerRefusal(ours));
                }
                return false;
            }

            private boolean refuse(String log, String message) {
                done = true;
                if (log != null) {
                    Log.warn("Communion: " + log + ".");
                }
                listener.disconnect(Component.literal(message));
                return false;
            }

            @Override
            public Type type() {
                return TASK;
            }
        }

        private static String name(ServerCommonPacketListenerImpl listener) {
            Object profile = profile(listener);
            if (profile != null) {
                for (String m : new String[] {"name", "getName"}) {
                    try {
                        Method get = profile.getClass().getMethod(m);
                        return String.valueOf(get.invoke(profile));
                    } catch (ReflectiveOperationException ignored) {
                        // the other spelling, then
                    }
                }
            }
            return "a player";
        }

        private static UUID uuid(ServerCommonPacketListenerImpl listener) {
            Object profile = profile(listener);
            if (profile != null) {
                for (String m : new String[] {"id", "getId"}) {
                    try {
                        return (UUID) profile.getClass().getMethod(m).invoke(profile);
                    } catch (ReflectiveOperationException | ClassCastException ignored) {
                        // the other spelling, then
                    }
                }
            }
            return null;
        }

        private static Object profile(Object listener) {
            try {
                Field f = field(ServerConfigurationPacketListenerImpl.class, com.mojang.authlib.GameProfile.class);
                return f.get(listener);
            } catch (ReflectiveOperationException | RuntimeException e) {
                return null;
            }
        }
    }

    // --- the client side (client classes; loaded only on a client, once the game runs) -----------

    /** Where communion is received: the client's half. */
    private static final class Pew {

        /** Connections that were offered communion: a server without it never is. */
        private static final Set<Object> OFFERED = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

        private Pew() {
        }

        static boolean offered(Object self, Object payload) {
            Manifest theirs;
            try {
                theirs = Altar.unwrap(payload);
            } catch (RuntimeException e) {
                Log.warn("MiracleToolChain: the server's communion manifest made no sense: " + e.getMessage());
                return true;
            }
            if (theirs == null) {
                return false;
            }
            Connection connection = connection(self);
            if (connection != null) {
                OFFERED.add(connection);
            }
            Manifest ours = Manifest.ours();
            // Answer first, so the server can log why, even when we leave right after.
            ((net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl) self).send(Altar.toServer(ours));
            List<String> wrong = judge(theirs, ours);
            if (!wrong.isEmpty()) {
                Log.warn("Communion refused: " + String.join("; ", wrong));
                if (connection != null) {
                    connection.disconnect(Component.literal(refusal(wrong)));
                }
            }
            return true;
        }

        static boolean refuseUnoffered(Object self) {
            if (!enabled) {
                return false;
            }
            Connection connection = connection(self);
            if (connection == null || OFFERED.contains(connection)) {
                return false;
            }
            Manifest ours = Manifest.ours();
            if (ours.bound().isEmpty()) {
                return false;
            }
            Log.warn("Communion: the server never offered it, and these mods need it: " + ours.bound());
            connection.disconnect(Component.literal(heathenRefusal(ours)));
            return true;
        }

        private static Connection connection(Object listener) {
            try {
                return (Connection) field(net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl.class,
                        Connection.class).get(listener);
            } catch (ReflectiveOperationException | RuntimeException e) {
                return null;
            }
        }
    }

    private static final Map<String, Field> FIELDS = new ConcurrentHashMap<>();

    /** The first field of this type declared by {@code owner}: found by type, so obfuscated names don't matter. */
    static Field field(Class<?> owner, Class<?> type) throws NoSuchFieldException {
        String key = owner.getName() + " " + type.getName();
        Field cached = FIELDS.get(key);
        if (cached != null) {
            return cached;
        }
        for (Field f : owner.getDeclaredFields()) {
            if (f.getType() == type && !java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                f.setAccessible(true);
                FIELDS.put(key, f);
                return f;
            }
        }
        throw new NoSuchFieldException(owner.getSimpleName() + " has no " + type.getSimpleName() + " field in this version");
    }
}
