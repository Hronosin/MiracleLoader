package io.github.hronosin.miracle.toolchain;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.function.Supplier;

/**
 * A new kind of block entity, made through {@link Creation#shrine}: the part of a block that
 * remembers things (an inventory, a counter, whose it is) and may act every tick. Known by its id
 * from the start, made for real when the game builds its registries.
 *
 * <pre>{@code
 * ALTAR = Creation.block("altar", p -> new Sanctuary(p.strength(2f)));
 * ALTAR_ENTITY = Creation.shrine("altar", AltarEntity::new, ALTAR);
 *
 * class AltarEntity extends Hallowed implements Vigil {
 *     AltarEntity(BlockPos pos, BlockState state) { super(ALTAR_ENTITY.get(), pos, state); }
 *     public void serverTick() { ... }
 * }
 * }</pre>
 *
 * <p>The blocks that hold it must be {@link net.minecraft.world.level.block.EntityBlock}s. The
 * easy way is a {@link Sanctuary}, which finds its shrine by itself, ticks {@link Vigil}s and opens
 * menus. A block entity of your own extends {@link Hallowed} (or {@link Reliquary} for an
 * inventory), or vanilla's {@code BlockEntity} if you'd rather do everything yourself.
 *
 * @param <T> the block entity's class
 */
public final class Shrine<T extends BlockEntity> implements Supplier<BlockEntityType<T>> {

    /** Makes a block entity for a block at a position: usually your class's constructor, {@code AltarEntity::new}. */
    @FunctionalInterface
    public interface Factory<T extends BlockEntity> {
        T create(BlockPos pos, BlockState state);
    }

    private final String namespace;
    private final String path;
    final Factory<T> factory;
    final List<Relic<Block>> blocks;
    private volatile BlockEntityType<T> value;
    /** Whether its block entities keep {@link Vigil}: learned from the first one made. */
    volatile Boolean vigilant;
    /** The container slot shown above the block, or -1. */
    volatile int enshrined = -1;
    volatile String renderer;
    volatile ClassLoader loader;

    Shrine(String namespace, String path, Factory<T> factory, List<Relic<Block>> blocks) {
        this.namespace = namespace;
        this.path = path;
        this.factory = factory;
        this.blocks = blocks;
    }

    /** The block entity type itself. Throws before the game has built its registries. */
    @Override
    public BlockEntityType<T> get() {
        BlockEntityType<T> v = value;
        if (v == null) {
            throw new IllegalStateException(this + " doesn't exist yet: the game makes it when it builds its registries,"
                    + " just after the loader hands over. Use it from game code, not from onLaunch().");
        }
        return v;
    }

    /** True once the game has made it. */
    public boolean exists() {
        return value != null;
    }

    /** A new block entity of this kind, as the block would make it. */
    public T create(BlockPos pos, BlockState state) {
        return get().create(pos, state);
    }

    /** Every block entity of this kind is made here, whether placed or loaded with its chunk. */
    T make(BlockPos pos, BlockState state) {
        T be = factory.create(pos, state);
        if (vigilant == null && be != null) {
            vigilant = be instanceof Vigil;
        }
        return be;
    }

    /**
     * Shows the item in container slot {@code slot} floating above the block, turning slowly, like
     * a relic on display. For block entities that are {@code Container}s (a {@link Reliquary} is);
     * the client needs to know the item, so {@link Reliquary#sync()} after changing it. The same on
     * every supported version.
     */
    public Shrine<T> enshrines(int slot) {
        Creation.checkOpen("Shrine.enshrines");
        if (slot < 0) {
            throw new IllegalArgumentException("slot " + slot + ": slots count from 0");
        }
        this.enshrined = slot;
        return this;
    }

    /**
     * Drawn by your own renderer, named so a dedicated server never loads it: a class with a
     * public constructor taking {@code BlockEntityRendererProvider.Context} that extends
     * {@link Altarpiece} (works on every supported version) or implements the game's
     * {@code BlockEntityRenderer} itself (full control, but its {@code submit} takes a
     * {@code CameraRenderState}, which moved between 1.21.11 and 26.1).
     */
    public Shrine<T> renderedBy(String rendererClass) {
        Creation.checkOpen("Shrine.renderedBy");
        this.renderer = rendererClass;
        this.loader = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).getCallerClass().getClassLoader();
        return this;
    }

    /** {@code namespace:path}, e.g. {@code hallelujah:altar}. */
    public Identifier id() {
        return Identifier.fromNamespaceAndPath(namespace, path);
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

    void set(BlockEntityType<T> v) {
        value = v;
    }
}
