package io.github.hronosin.miracle.toolchain;

import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.item.Item;

import java.util.function.Supplier;

/**
 * A new kind of entity, made through {@link Creation#entity}: known by its id from the start,
 * made for real when the game builds its registries. Its attributes, spawn egg and looks are set
 * here, all in {@code onLaunch()}:
 *
 * <pre>{@code
 * HERETIC = Creation.entity("heretic", () -> EntityType.Builder.of(Heretic::new, MobCategory.MONSTER).sized(0.6f, 1.95f))
 *         .attributes(() -> Zombie.createAttributes())
 *         .looksLike("zombie")
 *         .spawnEgg();
 * }</pre>
 *
 * <p><b>Looks.</b> A dedicated server has no renderers at all, so looks are named, not passed:
 * one jar serves both sides. {@link #looksLike(String)} borrows a vanilla entity's renderer (your
 * class should extend that entity's class, since the renderer reads its fields),
 * {@link #looksLikeItem()} draws a thrown item (for {@code ThrowableItemProjectile}s and other
 * {@code ItemSupplier}s), and {@link #renderedBy(String)} names your own renderer class, made on
 * the client only. With none of these the entity exists but is invisible, and the log says so.
 *
 * @param <T> the entity's class
 */
public final class Being<T extends Entity> implements Supplier<EntityType<T>> {

    enum Looks { NONE, VANILLA, ITEM, CUSTOM }

    private final String namespace;
    private final String path;
    final Supplier<EntityType.Builder<T>> builder;
    private volatile EntityType<T> value;
    volatile Supplier<AttributeSupplier.Builder> attributes;
    volatile Relic<Item> egg;
    volatile Looks looks = Looks.NONE;
    volatile String looksLike;
    volatile ClassLoader loader;

    Being(String namespace, String path, Supplier<EntityType.Builder<T>> builder) {
        this.namespace = namespace;
        this.path = path;
        this.builder = builder;
    }

    /** The entity type itself. Throws before the game has built its registries. */
    @Override
    public EntityType<T> get() {
        EntityType<T> v = value;
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

    /** {@code namespace:path}, e.g. {@code hallelujah:heretic}. */
    public Identifier id() {
        return Identifier.fromNamespaceAndPath(namespace, path);
    }

    /**
     * Health, speed, attack and the rest, for living entities (and required for them: the game
     * refuses to make a living entity without). Called once, when the game builds its registries:
     * {@code () -> Zombie.createAttributes().add(Attributes.MAX_HEALTH, 30)}.
     */
    public Being<T> attributes(Supplier<AttributeSupplier.Builder> attributes) {
        Creation.checkOpen("Being.attributes");
        this.attributes = attributes;
        return this;
    }

    /**
     * A spawn egg for it, {@code <name>_spawn_egg}, in the spawn eggs tab. Its model is yours to
     * provide ({@code miracle scribe entity} writes one borrowing a vanilla egg).
     */
    public Being<T> spawnEgg() {
        Creation.checkOpen("Being.spawnEgg");
        if (egg == null) {
            egg = Creation.egg(this);
        }
        return this;
    }

    /** The spawn egg's item. Throws if {@link #spawnEgg()} wasn't called. */
    public Relic<Item> egg() {
        if (egg == null) {
            throw new IllegalStateException(this + " has no spawn egg; call spawnEgg() first");
        }
        return egg;
    }

    /**
     * Drawn like a vanilla entity: {@code "zombie"}, {@code "pig"}, {@code "minecraft:skeleton"}.
     * Your entity should extend that one's class.
     */
    public Being<T> looksLike(String vanillaEntity) {
        Creation.checkOpen("Being.looksLike");
        this.looks = Looks.VANILLA;
        this.looksLike = vanillaEntity.contains(":") ? vanillaEntity : "minecraft:" + vanillaEntity;
        return this;
    }

    /** Drawn as the item it carries, like a snowball in flight. For {@code ItemSupplier} entities. */
    public Being<T> looksLikeItem() {
        Creation.checkOpen("Being.looksLikeItem");
        this.looks = Looks.ITEM;
        return this;
    }

    /**
     * Drawn by your own renderer: the class name of an {@code EntityRenderer} with a constructor
     * taking {@code EntityRendererProvider.Context}. By name, so a dedicated server never loads it.
     */
    public Being<T> renderedBy(String rendererClass) {
        Creation.checkOpen("Being.renderedBy");
        this.looks = Looks.CUSTOM;
        this.looksLike = rendererClass;
        this.loader =StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).getCallerClass().getClassLoader();
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

    void set(EntityType<T> v) {
        value = v;
    }
}
