package io.github.hronosin.miracle.toolchain;

import io.github.hronosin.miracle.Log;
import io.github.hronosin.miracle.api.Mods;
import io.github.hronosin.miracle.rgct.Rgct;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Telepathy: messages between client and server. Known as {@link Networking} to the
 * non-psychic.
 *
 * <pre>{@code
 * static final Telepathy.Channel PRAYER = Telepathy.channel("hallelujah:prayer");
 *
 * public void onLaunch() {
 *     PRAYER.onServer((player, scroll) -> player.heal(scroll.readInt()));   // server side
 *     PRAYER.onClient(scroll -> ...);                                       // client side
 * }
 * // client:  PRAYER.toServer(new Scroll().writeInt(4));
 * // server:  PRAYER.toPlayer(player, new Scroll().writeString("heard"));
 * }</pre>
 *
 * <p><b>How it travels.</b> Messages aren't sent one by one. Each side collects what it has to
 * say during a tick and sends it all at the end of the tick, as one batch in one packet (split
 * only if it's too big for one). Channel names never cross the wire: a message carries a 32-bit
 * hash of its channel's name, and each side looks the hash up among the channels it knows. What
 * crosses is data only. Nothing received is ever run as code: a message can only reach a handler
 * the receiving side registered itself.
 *
 * <p><b>The Inquisition.</b> An honest client sends at most a few batches a tick, numbered one
 * after another. The server watches for batches out of that rhythm (replayed, reordered, skipped,
 * a flood in one tick), for channels it doesn't know, and for garbage, and treats them as heresy:
 * logged, and dropped or kicked as {@code config/miracle-toolchain.toml} says. It's a tripwire for
 * crude packet injectors, not an anti-cheat; the real defense is still a server that checks what
 * it's told (don't let the client say how much to heal).
 *
 * <p>Both sides need the mod (and the library). A vanilla client or server ignores these packets.
 */
public class Telepathy {

    static final String KEY = "telepathy";

    /** Room for our own framing within the game's per-packet limits (32 KiB up, 1 MiB down). */
    static final int MAX_UP = 32_000;
    static final int MAX_DOWN = 1_000_000;

    private static final Map<Integer, Channel> CHANNELS = new ConcurrentHashMap<>();

    /** What the server does with heresy: "log", "drop" or "kick". */
    static volatile String action = "drop";
    static volatile int batchesPerTick = 4;

    protected Telepathy() {
    }

    /**
     * The channel with this name ({@code namespace:path}, your mod id as the namespace). Same
     * name, same channel, on both sides. Can be kept in a static field.
     */
    public static Channel channel(String name) {
        if (!name.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("'" + name + "' isn't a channel name: namespace:path, lowercase");
        }
        int hash = hash(name);
        Channel c = CHANNELS.computeIfAbsent(hash, h -> new Channel(name, h));
        if (!c.name.equals(name)) {
            throw new IllegalStateException("Channels '" + c.name + "' and '" + name + "' hash to the same number."
                    + " A one-in-four-billion miracle; rename one of them.");
        }
        return c;
    }

    /** FNV-1a, 32 bits: small, stable, and the same on every JVM. */
    static int hash(String s) {
        int h = 0x811C9DC5;
        for (byte b : s.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            h ^= b & 0xFF;
            h *= 0x01000193;
        }
        return h;
    }

    /** One named line between the sides. */
    public static final class Channel {
        final String name;
        final int hash;
        final List<BiConsumer<ServerPlayer, Scroll>> server = new CopyOnWriteArrayList<>();
        final List<Consumer<Scroll>> client = new CopyOnWriteArrayList<>();

        private Channel(String name, int hash) {
            this.name = name;
            this.hash = hash;
        }

        public String name() {
            return name;
        }

        /** Server side: what to do when a client sends a scroll on this channel. On the server thread. */
        public Channel onServer(BiConsumer<ServerPlayer, Scroll> handler) {
            Faithful.check("Telepathy.Channel.onServer", KEY);
            server.add(handler);
            return this;
        }

        /** Client side: what to do when the server sends a scroll on this channel. On the client thread. */
        public Channel onClient(Consumer<Scroll> handler) {
            Faithful.check("Telepathy.Channel.onClient", KEY);
            client.add(handler);
            return this;
        }

        /**
         * Client side: sends a scroll to the server, with this tick's batch. False if there's no
         * server to send to (the title screen, or a dedicated server calling it).
         */
        public boolean toServer(Scroll scroll) {
            return Wire.toServer(this, scroll);
        }

        /** Server side: sends a scroll to one player, with this tick's batch. */
        public void toPlayer(ServerPlayer player, Scroll scroll) {
            Wire.toPlayer(player, this, scroll);
        }

        /** Server side: sends a scroll to every player on the server. */
        public void toEveryone(MinecraftServer server, Scroll scroll) {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                Wire.toPlayer(p, this, scroll);
            }
        }

        @Override
        public String toString() {
            return name;
        }
    }

    // --- installation (startup, in the library's name) ------------------------------------------

    static void install(Rgct rgct, boolean client) {
        // Our packets, in both directions: the game asks for a codec by id, we answer for ours.
        rgct.target("net.minecraft.network.protocol.common.custom.CustomPacketPayload$1")
                .method("findCodec", "(Lnet/minecraft/resources/Identifier;)Lnet/minecraft/network/codec/StreamCodec;")
                .interceptHead(ctx -> {
                    if (Wire.isOurs(ctx.arg(0))) {
                        ctx.cancel(Wire.codec());
                    }
                });
        rgct.target("net.minecraft.server.network.ServerGamePacketListenerImpl")
                .method("handleCustomPayload", "(Lnet/minecraft/network/protocol/common/ServerboundCustomPayloadPacket;)V")
                .interceptHead(ctx -> Wire.receivedOnServer(ctx.self(), ctx.arg(0)));
        rgct.target("net.minecraft.server.MinecraftServer")
                .method("tickServer", "(Ljava/util/function/BooleanSupplier;)V")
                .atReturn(self -> Wire.flushServer(self));
        if (client) {
            rgct.target("net.minecraft.client.multiplayer.ClientPacketListener")
                    .method("handleCustomPayload", "(Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;)V")
                    .interceptHead(ctx -> {
                        if (Wire.receivedOnClient(ctx.arg(0))) {
                            ctx.cancel();
                        }
                    });
            rgct.target("net.minecraft.client.Minecraft")
                    .method("tick", "()V")
                    .atReturn(self -> Wire.flushClient(self));
        }
    }

    /**
     * Everything that touches game classes, apart from Telepathy itself so that loading Telepathy
     * at startup loads none of them.
     */
    private static final class Wire {

        private static final Identifier ID = Identifier.fromNamespaceAndPath("miracle", "telepathy");
        private static final CustomPacketPayload.Type<Epistle> TYPE = new CustomPacketPayload.Type<>(ID);
        private static final StreamCodec<FriendlyByteBuf, Epistle> CODEC = new StreamCodec<>() {
            @Override
            public Epistle decode(FriendlyByteBuf buf) {
                int n = buf.readableBytes();
                if (n > MAX_DOWN + 1024) {
                    throw new IllegalArgumentException("Telepathy batch of " + n + " bytes: more than any honest side sends");
                }
                byte[] data = new byte[n];
                buf.readBytes(data);
                return new Epistle(data);
            }

            @Override
            public void encode(FriendlyByteBuf buf, Epistle e) {
                buf.writeBytes(e.data());
            }
        };

        /** The payload: one batch, as bytes. */
        record Epistle(byte[] data) implements CustomPacketPayload {
            @Override
            public Type<? extends CustomPacketPayload> type() {
                return TYPE;
            }
        }

        /** One side's pending messages, and its batch counter. */
        static final class Outbox {
            final List<byte[]> messages = new ArrayList<>();
            int size;
            long seq;
        }

        private static final Map<ServerPlayer, Outbox> DOWN = new WeakHashMap<>();
        private static final Map<ServerPlayer, Inquisition> SEEN = new WeakHashMap<>();
        private static final Outbox UP = new Outbox();
        private static Object upConnection;

        private Wire() {
        }

        static boolean isOurs(Object id) {
            return ID.equals(id);
        }

        static Object codec() {
            return CODEC;
        }

        // --- sending --------------------------------------------------------------------------

        static byte[] frame(Channel c, Scroll s) {
            return Litany.frame(c.hash, s.bytes());
        }

        static void toPlayer(ServerPlayer player, Channel c, Scroll s) {
            byte[] m = frame(c, s);
            if (m.length > MAX_DOWN - 16) {
                throw new IllegalArgumentException("A " + m.length + "-byte scroll on " + c + " is too big to send (1 MB at most)");
            }
            synchronized (DOWN) {
                Outbox o = DOWN.computeIfAbsent(player, p -> new Outbox());
                o.messages.add(m);
                o.size += m.length;
            }
        }

        static boolean toServer(Channel c, Scroll s) {
            if (!Mods.game().client()) {
                return false;
            }
            byte[] m = frame(c, s);
            if (m.length > MAX_UP - 16) {
                throw new IllegalArgumentException("A " + m.length + "-byte scroll on " + c
                        + " is too big for the way up (the server takes 32 KB per packet)");
            }
            synchronized (UP) {
                UP.messages.add(m);
                UP.size += m.length;
            }
            return Minecraft.getInstance().getConnection() != null;
        }

        static void flushServer(Object self) {
            List<Map.Entry<ServerPlayer, byte[]>> sends = new ArrayList<>();
            synchronized (DOWN) {
                for (var e : DOWN.entrySet()) {
                    for (byte[] batch : batches(e.getValue(), MAX_DOWN)) {
                        sends.add(Map.entry(e.getKey(), batch));
                    }
                }
            }
            for (var s : sends) {
                s.getKey().connection.send(new ClientboundCustomPayloadPacket(new Epistle(s.getValue())));
            }
        }

        static void flushClient(Object self) {
            ClientPacketListener connection = ((Minecraft) self).getConnection();
            List<byte[]> sends;
            synchronized (UP) {
                if (connection != upConnection) {
                    upConnection = connection;
                    UP.seq = 0; // a new connection counts from the start
                }
                if (connection == null) {
                    UP.messages.clear();
                    UP.size = 0;
                    return;
                }
                sends = batches(UP, MAX_UP);
            }
            for (byte[] batch : sends) {
                connection.send(new ServerboundCustomPayloadPacket(new Epistle(batch)));
            }
        }

        /** Empties an outbox into batches. */
        private static List<byte[]> batches(Outbox o, int max) {
            long[] seq = {o.seq};
            List<byte[]> out = Litany.batches(o.messages, seq, max);
            o.seq = seq[0];
            o.messages.clear();
            o.size = 0;
            return out;
        }

        // --- receiving ------------------------------------------------------------------------

        static void receivedOnServer(Object self, Object packet) {
            if (!(((ServerboundCustomPayloadPacket) packet).payload() instanceof Epistle e)) {
                return;
            }
            ServerPlayer player = ((ServerGamePacketListenerImpl) self).player;
            MinecraftServer server = player.level().getServer();
            server.execute(() -> judge(server, player, e.data()));
        }

        /** Server thread: the Inquisition looks at the batch, then the handlers get it. */
        private static void judge(MinecraftServer server, ServerPlayer player, byte[] data) {
            Inquisition inquisition;
            synchronized (SEEN) {
                inquisition = SEEN.computeIfAbsent(player, p -> new Inquisition());
            }
            Litany.Batch b;
            try {
                if (data.length > MAX_UP + 1024) {
                    throw new IllegalStateException(data.length + " bytes, above the client's limit");
                }
                b = Litany.parse(data);
            } catch (RuntimeException ex) {
                heresy(player, "sent a malformed batch (" + ex.getMessage() + ")");
                return; // even "log" can't process what can't be read
            }
            boolean heretic = false;
            for (String sin : inquisition.examine(b.seq(), server.getTickCount(), batchesPerTick)) {
                heretic |= heresy(player, sin);
            }
            if (heretic) {
                return;
            }
            for (var m : b.messages()) {
                Channel c = CHANNELS.get(m.hash());
                if (c == null) {
                    if (heresy(player, "spoke on a channel this server doesn't know (#" + Integer.toHexString(m.hash()) + ")")) {
                        return;
                    }
                    continue;
                }
                if (c.server.isEmpty()) {
                    Log.warn("MiracleToolChain: a message on " + c + " from " + player.getName().getString()
                            + ", but nothing listens on the server side");
                }
                for (BiConsumer<ServerPlayer, Scroll> h : c.server) {
                    try {
                        h.accept(player, Scroll.read(m.body()));
                    } catch (RuntimeException ex) {
                        // One broken handler mustn't take the connection, or the other mods' messages, with it.
                        Log.error("MiracleToolChain: a server handler on " + c + " failed on a message from "
                                + player.getName().getString() + ": " + ex);
                    }
                }
            }
        }

        /** Logs heresy and applies the configured penance. True if the batch must be dropped. */
        private static boolean heresy(ServerPlayer player, String what) {
            String who = player.getName().getString();
            Log.warn("Inquisition: " + who + " " + what + ". Penance: " + action + ".");
            switch (action) {
                case "log" -> {
                    return false;
                }
                case "kick" -> {
                    player.connection.disconnect(Component.literal("The Inquisition has questions about your packets."));
                    return true;
                }
                default -> {
                    return true;
                }
            }
        }

        static boolean receivedOnClient(Object payload) {
            if (!(payload instanceof Epistle e)) {
                return false;
            }
            Litany.Batch b;
            try {
                b = Litany.parse(e.data());
            } catch (RuntimeException ex) {
                Log.warn("MiracleToolChain: the server sent a malformed Telepathy batch: " + ex.getMessage());
                return true;
            }
            for (var m : b.messages()) {
                Channel c = CHANNELS.get(m.hash());
                if (c == null) {
                    continue; // a channel of a mod this client doesn't have
                }
                for (Consumer<Scroll> h : c.client) {
                    try {
                        h.accept(Scroll.read(m.body()));
                    } catch (RuntimeException ex) {
                        Log.error("MiracleToolChain: a client handler on " + c + " failed: " + ex);
                    }
                }
            }
            return true;
        }
    }
}
