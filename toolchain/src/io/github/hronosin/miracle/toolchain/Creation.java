package io.github.hronosin.miracle.toolchain;

import io.github.hronosin.miracle.Log;
import io.github.hronosin.miracle.api.Mods;
import io.github.hronosin.miracle.rgct.Rgct;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Creation: new items and blocks. Known as {@link Content} to the uninspired.
 *
 * <pre>{@code
 * static final Relic<Item> HOLY_WATER = ...;
 *
 * public void onLaunch() {
 *     HOLY_WATER = Creation.item("holy_water", p -> new Item(p.stacksTo(16))).inTab("food_and_drinks");
 *     ALTAR = Creation.block("altar", p -> new Block(p.strength(2f))).inTab("functional_blocks");
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
 * <p>Blocks and items travel between client and server as numbers, so both sides need the same
 * mods: a vanilla client can't join a server with new blocks.
 */
public class Creation {

    static final String KEY = "creation";

    private static final List<Relic<Block>> BLOCKS = new ArrayList<>();
    private static final List<Function<BlockBehaviour.Properties, ? extends Block>> BLOCK_MAKERS = new ArrayList<>();
    private static final List<Relic<Item>> ITEMS = new ArrayList<>();
    private static final List<Function<Item.Properties, ? extends Item>> ITEM_MAKERS = new ArrayList<>();
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

    private static <T> Relic<T> relic(String what, String name) {
        Mods.Mod mod = Faithful.check(what, KEY);
        if (done) {
            throw new IllegalStateException(mod.id() + " calls " + what + "(\"" + name + "\") after the game built its"
                    + " registries. They're frozen now. Create things in onLaunch().");
        }
        if (!name.matches("[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("'" + name + "': names are lowercase letters, digits and _ . / -");
        }
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

    static void install(Rgct rgct) {
        // After vanilla's own contents, before the registries freeze.
        rgct.target("net.minecraft.core.registries.BuiltInRegistries")
                .method("freeze", "()V")
                .atHead(self -> Workshop.create());
        rgct.target("net.minecraft.world.item.CreativeModeTab")
                .method("buildContents", "(Lnet/minecraft/world/item/CreativeModeTab$ItemDisplayParameters;)V")
                .atReturn(self -> Workshop.fillTab(self));
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
            if (!BLOCKS.isEmpty() || !ITEMS.isEmpty()) {
                Log.info("MiracleToolChain: created " + BLOCKS.size() + " block(s) and " + ITEMS.size() + " item(s). And it was good.");
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
}
