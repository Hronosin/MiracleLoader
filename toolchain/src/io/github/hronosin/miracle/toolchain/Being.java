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
 * the client only, and {@link #sculpted()} draws a model of your own from Blockbench. With none
 * of these the entity exists but is invisible, and the log says so.
 *
 * @param <T> the entity's class
 */
public final class Being<T extends Entity> implements Supplier<EntityType<T>> {

    enum Looks { NONE, VANILLA, ITEM, CUSTOM, SCULPTED }

    private final String namespace;
    private final String path;
    final Supplier<EntityType.Builder<T>> builder;
    private volatile EntityType<T> value;
    volatile Supplier<AttributeSupplier.Builder> attributes;
    volatile Relic<Item> egg;
    volatile Looks looks = Looks.NONE;
    volatile String looksLike;
    volatile ClassLoader loader;
    volatile String geometry;
    volatile String texture;
    /** Where it turns up by itself, if anywhere. */
    final java.util.List<Spawn> spawns = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** One {@link #spawns} rule. */
    record Spawn(int weight, int min, int max, java.util.List<String> biomes) {
    }

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

    /**
     * Drawn from a model of your own, made in Blockbench: a Bedrock geometry file at
     * {@code assets/<namespace>/geo/<name>.geo.json}, painted with
     * {@code assets/<namespace>/textures/entity/<name>.png}. Parts move by name: {@code head}
     * follows the gaze, parts with {@code leg} walk and parts with {@code arm} swing. For mobs.
     * {@code miracle scribe entity <name> --model} writes a starting pair.
     */
    public Being<T> sculpted() {
        return sculpted(namespace + ":" + path, namespace + ":textures/entity/" + path + ".png");
    }

    /**
     * {@link #sculpted()} from other files: {@code geometry} {@code "ns:name"} is
     * {@code assets/ns/geo/name.geo.json}, and {@code texture} is a full texture id
     * ({@code "ns:textures/entity/name.png"}). Beings may share a geometry.
     */
    public Being<T> sculpted(String geometry, String texture) {
        Creation.checkOpen("Being.sculpted");
        if (!geometry.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || !texture.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException(this + ": sculpted(\"" + geometry + "\", \"" + texture
                    + "\"): both are namespace:path ids");
        }
        this.looks = Looks.SCULPTED;
        this.geometry = geometry;
        this.texture = texture;
        return this;
    }

    /**
     * Turns up by itself, as vanilla mobs do: in groups of {@code min} to {@code max}, in the
     * given biomes ({@code "minecraft:plains"}, or a tag: {@code "#minecraft:is_overworld"}), with
     * {@code weight} against the other mobs of its category there (zombies are 95, skeletons 100,
     * endermen 10). What category it counts in, and how it spawns, come from its
     * {@code MobCategory}: a {@code MONSTER} needs darkness and counts against the monster cap, a
     * {@code CREATURE} wants grass and light, water categories spawn in water. Call it more than
     * once for different biomes and weights. During play only: new chunks aren't populated with it.
     */
    public Being<T> spawns(int weight, int min, int max, String... biomes) {
        Creation.checkOpen("Being.spawns");
        if (weight <= 0 || min <= 0 || max < min) {
            throw new IllegalArgumentException(this + ": spawns(" + weight + ", " + min + ", " + max
                    + ") needs weight > 0 and 0 < min <= max");
        }
        if (biomes.length == 0) {
            throw new IllegalArgumentException(this + ": spawns where? Name a biome or a #tag");
        }
        for (String b : biomes) {
            if (!b.replaceFirst("^#", "").matches("([a-z0-9_.-]+:)?[a-z0-9_./-]+")) {
                throw new IllegalArgumentException(this + ": '" + b + "' isn't a biome id or #tag");
            }
        }
        spawns.add(new Spawn(weight, min, max, java.util.List.of(biomes)));
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
