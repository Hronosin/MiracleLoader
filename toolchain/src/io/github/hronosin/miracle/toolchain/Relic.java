package io.github.hronosin.miracle.toolchain;

import net.minecraft.resources.Identifier;

import java.util.function.Supplier;

/**
 * Something a mod put into the world through {@link Creation}: an item or a block, known by its
 * id from the start and made for real when the game builds its registries. {@link #get()} works
 * from then on.
 *
 * @param <T> Item or Block
 */
public final class Relic<T> implements Supplier<T> {

    private final String namespace;
    private final String path;
    private volatile T value;
    private volatile Relic<net.minecraft.world.item.Item> item;
    volatile String tab;

    Relic(String namespace, String path) {
        this.namespace = namespace;
        this.path = path;
    }

    /** The thing itself. Throws before the game has built its registries. */
    @Override
    public T get() {
        T v = value;
        if (v == null) {
            throw new IllegalStateException(namespace + ":" + path + " doesn't exist yet: the game makes it when it builds"
                    + " its registries, just after the loader hands over. Use it from game code (omens, commands), not from onLaunch().");
        }
        return v;
    }

    /** True once the game has made it. */
    public boolean exists() {
        return value != null;
    }

    /** {@code namespace:path}, e.g. {@code hallelujah:holy_water}. */
    public Identifier id() {
        return Identifier.fromNamespaceAndPath(namespace, path);
    }

    /** The item that places this block ({@link Creation#block} makes one). Throws for items. */
    public Relic<net.minecraft.world.item.Item> item() {
        if (item == null) {
            throw new IllegalStateException(namespace + ":" + path + " has no item of its own");
        }
        return item;
    }

    /**
     * Shows it in a creative inventory tab: {@code building_blocks}, {@code colored_blocks},
     * {@code natural_blocks}, {@code functional_blocks}, {@code redstone_blocks},
     * {@code tools_and_utilities}, {@code combat}, {@code food_and_drinks}, {@code ingredients},
     * {@code spawn_eggs}, or any other tab's id. A block goes there as its item.
     */
    public Relic<T> inTab(String tabId) {
        this.tab = tabId.contains(":") ? tabId : "minecraft:" + tabId;
        return this;
    }

    @Override
    public String toString() {
        return namespace + ":" + path;
    }

    String namespace() {
        return namespace;
    }

    String path() {
        return path;
    }

    void set(T v) {
        value = v;
    }

    void item(Relic<net.minecraft.world.item.Item> i) {
        item = i;
    }
}
