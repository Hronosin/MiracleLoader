package io.github.hronosin.miracle.toolchain;

import io.github.hronosin.miracle.Log;
import io.github.hronosin.miracle.api.Mods;
import io.github.hronosin.miracle.rgct.Rgct;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.client.renderer.entity.NoopRenderer;
import net.minecraft.client.renderer.entity.ThrownItemRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Creation: new items, blocks, entities, block entities and menus. Known as {@link Content} to
 * the uninspired.
 *
 * <pre>{@code
 * static Relic<Item> HOLY_WATER;
 *
 * public void onLaunch() {
 *     HOLY_WATER = Creation.item("holy_water", p -> new Item(p.stacksTo(16))).inTab("food_and_drinks");
 *     ALTAR = Creation.block("altar", p -> new Block(p.strength(2f))).inTab("functional_blocks");
 *     HERETIC = Creation.entity("heretic", () -> EntityType.Builder.of(Heretic::new, MobCategory.MONSTER))
 *             .attributes(() -> Zombie.createAttributes()).looksLike("zombie").spawnEgg();
 * }
 * }</pre>
 *
 * <p>Ids are {@code <namespace>:<name>}, the namespace being your mod id with {@code -} as
 * {@code _} ({@code holy-hops} makes {@code holy_hops:altar}). The game makes everything when it
 * builds its registries, right after the loader hands over; call Creation from
 * {@code onLaunch()}, then use the {@link Relic}s from game code. The properties passed to your
 * factory already carry the id, as the game requires since 1.21.2.
 *
 * <p>Everything else the game needs comes from your jar's {@code resources/}, revealed
 * automatically for a mod that creates things (see {@link Scripture}): models and textures
 * ({@code assets/<namespace>/items}, {@code models}, {@code textures}, {@code blockstates}),
 * names ({@code assets/<namespace>/lang/en_us.json}), and for blocks a loot table
 * ({@code data/<namespace>/loot_table/blocks/<name>.json}) or they drop nothing.
 * {@code miracle scribe item|block <name>} writes a starting set.
 *
 * <p>Entities are {@link Being}s: their attributes, spawn egg and looks are set there. Their
 * names are {@code entity.<namespace>.<name>} in the lang file, and a mob's drops come from
 * {@code data/<namespace>/loot_table/entities/<name>.json}.
 *
 * <p>Block entities are {@link Shrine}s, held by blocks that are {@link Sanctuary Sanctuaries}
 * (or any {@code EntityBlock}); a {@link Reliquary} is one with an inventory. Menus are
 * {@link Vision}s, with their screens.
 *
 * <p>All of these travel between client and server as numbers, so both sides need the same
 * mods: {@link Communion} checks that when a player joins.
 */
public class Creation {

    static final String KEY = "creation";

    private static final List<Relic<Block>> BLOCKS = new ArrayList<>();
    private static final List<Function<BlockBehaviour.Properties, ? extends Block>> BLOCK_MAKERS = new ArrayList<>();
    private static final List<Relic<Item>> ITEMS = new ArrayList<>();
    private static final List<Function<Item.Properties, ? extends Item>> ITEM_MAKERS = new ArrayList<>();
    private static final List<Being<?>> BEINGS = new ArrayList<>();
    private static final List<Shrine<?>> SHRINES = new ArrayList<>();
    private static final List<Vision<?>> VISIONS = new ArrayList<>();
    /** Filled when the registries are built; read by Sanctuaries and screens. */
    private static final Map<Block, Shrine<?>> SHRINE_OF = new IdentityHashMap<>();
    private static final Map<Object, Vision<?>> VISION_OF = new IdentityHashMap<>();
    private static volatile boolean done;

    protected Creation() {
    }

    /** A plain item. */
    public static Relic<Item> item(String name) {
        return item(name, Item::new);
    }

    /** An item made by {@code factory} from properties that already carry its id. */
    public static synchronized Relic<Item> item(String name, Function<Item.Properties, ? extends Item> factory) {
        Relic<Item> r = relic("Creation.item", name);
        ITEMS.add(r);
        ITEM_MAKERS.add(factory);
        return r;
    }

    /** A plain block, strength 1 (like dirt, give or take), with an item to place it. */
    public static Relic<Block> block(String name) {
        return block(name, p -> new Block(p.strength(1f)));
    }

    /**
     * A block made by {@code factory}, and an item that places it ({@link Relic#item()}), named
     * like the block.
     */
    public static synchronized Relic<Block> block(String name, Function<BlockBehaviour.Properties, ? extends Block> factory) {
        Relic<Block> r = relic("Creation.block", name);
        BLOCKS.add(r);
        BLOCK_MAKERS.add(factory);
        Relic<Item> item = new Relic<>(r.namespace(), r.path());
        r.item(item);
        ITEMS.add(item);
        ITEM_MAKERS.add(null); // a block item: made from its block
        return r;
    }

    /**
     * A new kind of entity, made from the builder {@code builder} gives when the game builds its
     * registries (a supplier, because the builder touches game classes that can't be touched
     * this early). Set its attributes, spawn egg and looks on the {@link Being} this returns.
     */
    public static synchronized <T extends Entity> Being<T> entity(String name, Supplier<EntityType.Builder<T>> builder) {
        Mods.Mod mod = open("Creation.entity", name);
        String ns = namespace(mod.id());
        for (Being<?> b : BEINGS) {
            if (b.namespace().equals(ns) && b.path().equals(name)) {
                throw new IllegalArgumentException(ns + ":" + name + " was already created. Once is enough.");
            }
        }
        Being<T> b = new Being<>(ns, name, builder);
        BEINGS.add(b);
        return b;
    }

    /**
     * A new kind of block entity, held by {@code blocks} (at least one; they must be
     * {@code EntityBlock}s, like a {@link Sanctuary}). {@code factory} makes one for a block at a
     * position: usually your block entity's constructor, {@code AltarEntity::new}.
     */
    @SafeVarargs
    public static synchronized <T extends BlockEntity> Shrine<T> shrine(String name, Shrine.Factory<T> factory,
                                                                        Relic<Block>... blocks) {
        Mods.Mod mod = open("Creation.shrine", name);
        if (blocks.length == 0) {
            throw new IllegalArgumentException(name + ": a shrine needs at least one block to stand in");
        }
        String ns = namespace(mod.id());
        for (Shrine<?> s : SHRINES) {
            if (s.namespace().equals(ns) && s.path().equals(name)) {
                throw new IllegalArgumentException(ns + ":" + name + " was already created. Once is enough.");
            }
        }
        for (Relic<Block> b : blocks) {
            if (!BLOCKS.contains(b)) {
                throw new IllegalArgumentException(b + " isn't a block made with Creation.block (an item, or a vanilla"
                        + " block?): " + ns + ":" + name + " can only stand in blocks of your own");
            }
            for (Shrine<?> s : SHRINES) {
                if (s.blocks.contains(b)) {
                    throw new IllegalArgumentException(b + " already holds " + s + "; a block holds one kind of block entity");
                }
            }
        }
        List<Relic<Block>> holders = new ArrayList<>();
        for (Relic<Block> b : blocks) {
            holders.add(b);
        }
        Shrine<T> s = new Shrine<>(ns, name, factory, List.copyOf(holders));
        SHRINES.add(s);
        return s;
    }

    /**
     * A block entity with an inventory of {@code rows} rows of nine (1 to 6), opened in the chest
     * screen: a {@link Reliquary}, held by {@code blocks}. For a chest of your own with no other
     * code; extend Reliquary and use {@link #shrine} for more.
     */
    @SafeVarargs
    public static Shrine<Reliquary> reliquary(String name, int rows, Relic<Block>... blocks) {
        if (rows < 1 || rows > 6) {
            throw new IllegalArgumentException(name + ": " + rows + " rows; a reliquary has 1 to 6");
        }
        @SuppressWarnings("unchecked")
        Shrine<Reliquary>[] self = (Shrine<Reliquary>[]) new Shrine<?>[1];
        self[0] = shrine(name, (pos, state) -> new Reliquary(self[0].get(), pos, state, rows), blocks);
        return self[0];
    }

    /**
     * A new kind of menu. {@code factory} makes the client's half from an id and the player's
     * inventory (usually a constructor, {@code AltarMenu::new}); see {@link Vision} for the rest.
     */
    public static synchronized <M extends AbstractContainerMenu> Vision<M> vision(String name, Vision.Factory<M> factory) {
        Mods.Mod mod = open("Creation.vision", name);
        String ns = namespace(mod.id());
        for (Vision<?> v : VISIONS) {
            if (v.namespace().equals(ns) && v.path().equals(name)) {
                throw new IllegalArgumentException(ns + ":" + name + " was already created. Once is enough.");
            }
        }
        Vision<M> v = new Vision<>(ns, name, factory);
        VISIONS.add(v);
        return v;
    }

    /** The shrine a block holds, or null. */
    static Shrine<?> shrineOf(Block block) {
        synchronized (SHRINE_OF) {
            return SHRINE_OF.get(block);
        }
    }

    /** The vision of a menu type, or null for vanilla's and other loaders'. */
    static Vision<?> visionOf(Object menuType) {
        synchronized (VISION_OF) {
            return VISION_OF.get(menuType);
        }
    }

    /** The spawn egg of a being: an item like any other, made once the entity exists. */
    static synchronized Relic<Item> egg(Being<?> being) {
        Relic<Item> r = relic("Being.spawnEgg", being.path() + "_spawn_egg");
        ITEMS.add(r);
        ITEM_MAKERS.add(p -> new SpawnEggItem(p.spawnEgg(being.get())));
        return r.inTab("spawn_eggs");
    }

    /** Throws if the registries are already built. For the setters on Relic and Being. */
    static void checkOpen(String what) {
        if (done) {
            throw new IllegalStateException(what + " after the game built its registries: too late. Do it in onLaunch().");
        }
    }

    /** True for the id of an entity some mod created ("hallelujah:heretic"). */
    static boolean isBeing(Object id) {
        if (!(id instanceof String s)) {
            return false;
        }
        synchronized (Creation.class) {
            for (Being<?> b : BEINGS) {
                if (b.toString().equals(s)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Every id created, in the order the game numbers them. The same on both sides, or they can't play. */
    static synchronized List<String> inventory() {
        List<String> ids = new ArrayList<>();
        BLOCKS.forEach(r -> ids.add("block " + r));
        BEINGS.forEach(b -> ids.add("entity " + b));
        ITEMS.forEach(r -> ids.add("item " + r));
        SHRINES.forEach(r -> ids.add("block_entity " + r));
        VISIONS.forEach(v -> ids.add("menu " + v));
        return ids;
    }

    private static Mods.Mod open(String what, String name) {
        Mods.Mod mod = Faithful.check(what, KEY);
        if (done) {
            throw new IllegalStateException(mod.id() + " calls " + what + "(\"" + name + "\") after the game built its"
                    + " registries. They're frozen now. Create things in onLaunch().");
        }
        if (!name.matches("[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("'" + name + "': names are lowercase letters, digits and _ . / -");
        }
        return mod;
    }

    private static <T> Relic<T> relic(String what, String name) {
        Mods.Mod mod = open(what, name);
        String ns = namespace(mod.id());
        for (Relic<?> r : ITEMS) {
            if (r.namespace().equals(ns) && r.path().equals(name)) {
                throw new IllegalArgumentException(ns + ":" + name + " was already created. Once is enough.");
            }
        }
        return new Relic<>(ns, name);
    }

    /** A mod's namespace: its id, with - as _ (my-mod makes my_mod:thing), like its data and assets folders. */
    static String namespace(String modId) {
        return modId.replace('-', '_');
    }

    // --- installation (startup, in the library's name) ------------------------------------------

    static void install(Rgct rgct, boolean beings, boolean visions, boolean client) {
        // After vanilla's own contents, before the registries freeze.
        rgct.target("net.minecraft.core.registries.BuiltInRegistries")
                .method("freeze", "()V")
                .atHead(self -> Workshop.create());
        rgct.target("net.minecraft.world.item.CreativeModeTab")
                .method("buildContents", "(Lnet/minecraft/world/item/CreativeModeTab$ItemDisplayParameters;)V")
                .atReturn(self -> Workshop.fillTab(self));
        if (visions && client) {
            // The server names a menu type; the client makes its screen. Ours, we make.
            rgct.target("net.minecraft.client.gui.screens.MenuScreens")
                    .method("create", "(Lnet/minecraft/world/inventory/MenuType;Lnet/minecraft/client/Minecraft;"
                            + "ILnet/minecraft/network/chat/Component;)V")
                    .interceptHead(ctx -> {
                        if (visionOf(ctx.arg(0)) != null) {
                            Seer.show(ctx.arg(0), ctx.arg(2), ctx.arg(3));
                            ctx.cancel();
                        }
                    });
            Easel.install(rgct);
        }
        if (!beings) {
            return;
        }
        // Vanilla's entity types each have a save-data fixer; ours don't need one, and shouldn't
        // make the builder log an ERROR saying so.
        rgct.target("net.minecraft.util.Util")
                .method("fetchChoiceType", "(Lcom/mojang/datafixers/DSL$TypeReference;Ljava/lang/String;)Lcom/mojang/datafixers/types/Type;")
                .interceptHead(ctx -> {
                    if (isBeing(ctx.arg(1))) {
                        ctx.cancel(null);
                    }
                });
        // Living entities get their attributes from a map vanilla fills once; ours answer first.
        rgct.target("net.minecraft.world.entity.ai.attributes.DefaultAttributes")
                .method("getSupplier", "(Lnet/minecraft/world/entity/EntityType;)Lnet/minecraft/world/entity/ai/attributes/AttributeSupplier;")
                .interceptHead(ctx -> {
                    Object a = Workshop.attributesOf(ctx.arg(0));
                    if (a != null) {
                        ctx.cancel(a);
                    }
                });
        rgct.target("net.minecraft.world.entity.ai.attributes.DefaultAttributes")
                .method("hasSupplier", "(Lnet/minecraft/world/entity/EntityType;)Z")
                .interceptHead(ctx -> {
                    if (Workshop.attributesOf(ctx.arg(0)) != null) {
                        ctx.cancel(true);
                    }
                });
        if (client) {
            // Renderers are made from a map of providers; ours join it before anyone reads it.
            rgct.target("net.minecraft.client.renderer.entity.EntityRenderers")
                    .method("createEntityRenderers",
                            "(Lnet/minecraft/client/renderer/entity/EntityRendererProvider$Context;)Ljava/util/Map;")
                    .atHead(self -> Studio.enlist());
            rgct.target("net.minecraft.client.renderer.entity.EntityRenderers")
                    .method("validateRegistrations", "()Z")
                    .atHead(self -> Studio.enlist());
        }
    }

    /**
     * The code that touches game classes, kept apart so that loading Creation during startup
     * loads none of them: the JVM checks a class's code when it loads it, and that can pull in
     * the classes it mentions.
     */
    private static final class Workshop {

        private Workshop() {
        }

        static void create() {
            synchronized (Creation.class) {
                createLocked();
            }
        }

        /** Our entity types' attributes: the recipe until first asked for, then the result. */
        private static final Map<EntityType<?>, Object> ATTRIBUTES = new IdentityHashMap<>();

        /**
         * Built on first use, not at registration: attributes are registry entries that can't be
         * read until the registries are frozen, which is right after we register.
         */
        @SuppressWarnings("unchecked")
        static Object attributesOf(Object type) {
            synchronized (ATTRIBUTES) {
                Object a = ATTRIBUTES.get(type);
                if (a instanceof Supplier<?> recipe) {
                    a = ((Supplier<AttributeSupplier.Builder>) recipe).get().build();
                    ATTRIBUTES.put((EntityType<?>) type, a);
                }
                return a;
            }
        }

        private static void createLocked() {
            if (done) {
                return;
            }
            done = true;
            for (int i = 0; i < BLOCKS.size(); i++) {
                Relic<Block> r = BLOCKS.get(i);
                ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, r.id());
                Block block = BLOCK_MAKERS.get(i).apply(BlockBehaviour.Properties.of().setId(key));
                r.set(Registry.register(BuiltInRegistries.BLOCK, key, block));
                // Vanilla numbers every block state for the network before we get here; ours join
                // the end of the list, in the same order on every side that has the same mods.
                for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                    Block.BLOCK_STATE_REGISTRY.add(state);
                    state.initCache();
                }
            }
            for (Shrine<?> s : SHRINES) {
                createShrine(s);
            }
            for (Being<?> b : BEINGS) {
                createBeing(b);
            }
            for (int i = 0; i < ITEMS.size(); i++) {
                Relic<Item> r = ITEMS.get(i);
                ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, r.id());
                Item.Properties props = new Item.Properties().setId(key);
                Function<Item.Properties, ? extends Item> maker = ITEM_MAKERS.get(i);
                Item item;
                if (maker != null) {
                    item = maker.apply(props);
                } else {
                    BlockItem blockItem = new BlockItem(blockFor(r), props.useBlockDescriptionPrefix());
                    blockItem.registerBlocks(Item.BY_BLOCK, blockItem); // so block.asItem() finds it
                    item = blockItem;
                }
                r.set(Registry.register(BuiltInRegistries.ITEM, key, item));
            }
            for (Vision<?> v : VISIONS) {
                createVision(v);
            }
            if (!BLOCKS.isEmpty() || !ITEMS.isEmpty() || !BEINGS.isEmpty() || !SHRINES.isEmpty() || !VISIONS.isEmpty()) {
                StringBuilder what = new StringBuilder("MiracleToolChain: created " + BLOCKS.size() + " block(s), "
                        + ITEMS.size() + " item(s), " + BEINGS.size() + " kind(s) of entity");
                if (!SHRINES.isEmpty() || !VISIONS.isEmpty()) {
                    what.append(", ").append(SHRINES.size()).append(" kind(s) of block entity and ")
                            .append(VISIONS.size()).append(" menu(s)");
                }
                Log.info(what.append(". And it was good.").toString());
            }
        }

        /**
         * Vanilla makes block entity types through a constructor that's private in 1.21.11, taking
         * a factory interface that's package-private there: both are reached by shape, not name.
         */
        @SuppressWarnings("unchecked")
        private static <T extends BlockEntity> void createShrine(Shrine<T> s) {
            ResourceKey<BlockEntityType<?>> key = ResourceKey.create(Registries.BLOCK_ENTITY_TYPE, s.id());
            Block[] blocks = s.blocks.stream().map(Relic::get).toArray(Block[]::new);
            BlockEntityType<T> type;
            try {
                Constructor<?> ctor = twoArgs(BlockEntityType.class, Set.class);
                Object supplier = factory(ctor.getParameterTypes()[0], s.toString(),
                        args -> s.make((net.minecraft.core.BlockPos) args[0], (BlockState) args[1]));
                type = (BlockEntityType<T>) ctor.newInstance(supplier, Set.of(blocks));
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("MiracleToolChain can't make block entity types in this version: " + e, e);
            }
            s.set(Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, key, type));
            synchronized (SHRINE_OF) {
                for (Block b : blocks) {
                    SHRINE_OF.put(b, s);
                    if (!(b instanceof net.minecraft.world.level.block.EntityBlock)) {
                        Log.warn("MiracleToolChain: " + s + " stands in " + BuiltInRegistries.BLOCK.getKey(b) + ", which isn't"
                                + " an EntityBlock (a Sanctuary is), so it never makes one.");
                    }
                }
            }
        }

        /** Menu types too: private constructor, package-private factory interface. */
        @SuppressWarnings("unchecked")
        private static <M extends AbstractContainerMenu> void createVision(Vision<M> v) {
            ResourceKey<MenuType<?>> key = ResourceKey.create(Registries.MENU, v.id());
            MenuType<M> type;
            try {
                Constructor<?> ctor = twoArgs(MenuType.class, FeatureFlagSet.class);
                Object supplier = factory(ctor.getParameterTypes()[0], v.toString(),
                        args -> v.factory.create((Integer) args[0], (net.minecraft.world.entity.player.Inventory) args[1]));
                type = (MenuType<M>) ctor.newInstance(supplier, FeatureFlagSet.of());
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("MiracleToolChain can't make menu types in this version: " + e, e);
            }
            v.set(Registry.register(BuiltInRegistries.MENU, key, type));
            synchronized (VISION_OF) {
                VISION_OF.put(type, v);
            }
        }

        /** The constructor of {@code owner} taking (some factory interface, {@code second}). */
        private static Constructor<?> twoArgs(Class<?> owner, Class<?> second) throws NoSuchMethodException {
            for (Constructor<?> c : owner.getDeclaredConstructors()) {
                Class<?>[] p = c.getParameterTypes();
                if (p.length == 2 && p[0].isInterface() && p[1] == second) {
                    c.setAccessible(true);
                    return c;
                }
            }
            throw new NoSuchMethodException(owner.getSimpleName() + "(<factory>, " + second.getSimpleName() + ")");
        }

        /** An instance of a one-method factory interface, public or not, that calls {@code body}. */
        private static Object factory(Class<?> iface, String name, Function<Object[], Object> body) {
            return Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) {
                    return switch (method.getName()) {
                        case "equals" -> proxy == args[0];
                        case "hashCode" -> System.identityHashCode(proxy);
                        default -> "MiracleToolChain factory for " + name;
                    };
                }
                return body.apply(args == null ? new Object[0] : Arrays.copyOf(args, args.length));
            });
        }

        private static <T extends Entity> void createBeing(Being<T> b) {
            ResourceKey<EntityType<?>> key = ResourceKey.create(Registries.ENTITY_TYPE, b.id());
            EntityType<T> type = b.builder.get().build(key);
            b.set(Registry.register(BuiltInRegistries.ENTITY_TYPE, key, type));
            if (b.attributes != null) {
                synchronized (ATTRIBUTES) {
                    ATTRIBUTES.put(type, b.attributes);
                }
            }
        }

        private static Block blockFor(Relic<Item> item) {
            for (Relic<Block> b : BLOCKS) {
                if (b.item() == item) {
                    return b.get();
                }
            }
            throw new IllegalStateException("no block for " + item);
        }

        /** Adds our items to a creative tab the game just filled. */
        static void fillTab(Object self) {
            CreativeModeTab tab = (CreativeModeTab) self;
            Identifier id = BuiltInRegistries.CREATIVE_MODE_TAB.getKey(tab);
            if (id == null) {
                return;
            }
            List<ItemStack> ours = new ArrayList<>();
            for (Relic<Block> b : BLOCKS) {
                if (id.toString().equals(b.tab)) {
                    ours.add(new ItemStack(b.item().get()));
                }
            }
            for (Relic<Item> i : ITEMS) {
                if (id.toString().equals(i.tab)) {
                    ours.add(new ItemStack(i.get()));
                }
            }
            if (!ours.isEmpty()) {
                TabFields.add(tab, ours);
            }
        }

        /** The tab's item lists are private; found by type, so obfuscated names don't matter. */
        private static final class TabFields {
            private static final Field DISPLAY = field(Collection.class, Set.class);
            private static final Field SEARCH = field(Set.class, null);

            @SuppressWarnings("unchecked")
            static void add(CreativeModeTab tab, List<ItemStack> stacks) {
                try {
                    ((Collection<ItemStack>) DISPLAY.get(tab)).addAll(stacks);
                    ((Collection<ItemStack>) SEARCH.get(tab)).addAll(stacks);
                } catch (IllegalAccessException | RuntimeException e) {
                    Log.warn("MiracleToolChain: couldn't add to the creative tab: " + e);
                }
            }

            private static Field field(Class<?> type, Class<?> not) {
                for (Field f : CreativeModeTab.class.getDeclaredFields()) {
                    if (f.getType() == type && f.getType() != not) {
                        f.setAccessible(true);
                        return f;
                    }
                }
                throw new IllegalStateException("CreativeModeTab has no " + type.getSimpleName() + " field in this version");
            }
        }
    }

    /** The client-only half: renderers. Loaded only on a client, once the game runs. */
    private static final class Studio {

        private static boolean enlisted;

        private Studio() {
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        static synchronized void enlist() {
            if (enlisted || BEINGS.isEmpty()) {
                return;
            }
            enlisted = true;
            Map<EntityType<?>, EntityRendererProvider<?>> providers;
            try {
                providers = (Map<EntityType<?>, EntityRendererProvider<?>>) staticMap(EntityRenderers.class).get(null);
            } catch (ReflectiveOperationException | RuntimeException e) {
                Log.warn("MiracleToolChain: couldn't reach the entity renderers, new entities will be invisible: " + e);
                return;
            }
            for (Being<?> b : BEINGS) {
                if (!b.exists() || providers.containsKey(b.get())) {
                    continue;
                }
                EntityRendererProvider<?> p = switch (b.looks) {
                    case VANILLA -> {
                        EntityType<?> like = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse(b.looksLike));
                        EntityRendererProvider<?> theirs = like == null ? null : providers.get(like);
                        if (theirs == null) {
                            Log.warn("MiracleToolChain: " + b + " wants to look like " + b.looksLike
                                    + ", which this game can't draw. It will be invisible.");
                            yield NoopRenderer::new;
                        }
                        yield theirs;
                    }
                    case ITEM -> ctx -> new ThrownItemRenderer(ctx);
                    case CUSTOM -> custom(b);
                    case NONE -> {
                        Log.warn("MiracleToolChain: " + b + " has no looks (looksLike, looksLikeItem or renderedBy),"
                                + " so it's invisible. Spooky, but probably not what you meant.");
                        yield NoopRenderer::new;
                    }
                };
                providers.put(b.get(), p);
            }
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private static EntityRendererProvider<?> custom(Being<?> b) {
            return (EntityRendererProvider) ctx -> {
                try {
                    Class<?> cls = Class.forName(b.looksLike, true, b.loader);
                    return (net.minecraft.client.renderer.entity.EntityRenderer)
                            cls.getConstructor(EntityRendererProvider.Context.class).newInstance(ctx);
                } catch (ReflectiveOperationException | ClassCastException e) {
                    Log.error("MiracleToolChain: " + b + " is rendered by " + b.looksLike + ", which couldn't be made"
                            + " (an EntityRenderer with a constructor taking EntityRendererProvider.Context?): " + e);
                    return new NoopRenderer<>(ctx);
                }
            };
        }

        private static Field staticMap(Class<?> owner) throws NoSuchFieldException {
            for (Field f : owner.getDeclaredFields()) {
                if (f.getType() == Map.class && java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    f.setAccessible(true);
                    return f;
                }
            }
            throw new NoSuchFieldException(owner.getSimpleName() + " has no static Map in this version");
        }
    }
}
