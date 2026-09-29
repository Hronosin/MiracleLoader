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
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Creation: new items, blocks and entities. Known as {@link Content} to the uninspired.
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
 * <p>Blocks, items and entities travel between client and server as numbers, so both sides need
 * the same mods: {@link Communion} checks that when a player joins.
 */
public class Creation {

    static final String KEY = "creation";

    private static final List<Relic<Block>> BLOCKS = new ArrayList<>();
    private static final List<Function<BlockBehaviour.Properties, ? extends Block>> BLOCK_MAKERS = new ArrayList<>();
    private static final List<Relic<Item>> ITEMS = new ArrayList<>();
    private static final List<Function<Item.Properties, ? extends Item>> ITEM_MAKERS = new ArrayList<>();
    private static final List<Being<?>> BEINGS = new ArrayList<>();
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

    static void install(Rgct rgct, boolean beings, boolean client) {
        // After vanilla's own contents, before the registries freeze.
        rgct.target("net.minecraft.core.registries.BuiltInRegistries")
                .method("freeze", "()V")
                .atHead(self -> Workshop.create());
        rgct.target("net.minecraft.world.item.CreativeModeTab")
                .method("buildContents", "(Lnet/minecraft/world/item/CreativeModeTab$ItemDisplayParameters;)V")
                .atReturn(self -> Workshop.fillTab(self));
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
            if (!BLOCKS.isEmpty() || !ITEMS.isEmpty() || !BEINGS.isEmpty()) {
                Log.info("MiracleToolChain: created " + BLOCKS.size() + " block(s), " + ITEMS.size() + " item(s) and "
                        + BEINGS.size() + " kind(s) of entity. And it was good.");
            }
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
