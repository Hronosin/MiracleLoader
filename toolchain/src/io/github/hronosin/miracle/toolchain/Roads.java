package io.github.hronosin.miracle.toolchain;

import io.github.hronosin.miracle.Log;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.util.random.Weighted;
import net.minecraft.util.random.WeightedList;
import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.SpawnPlacementTypes;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.levelgen.Heightmap;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/** The game-facing half of {@link Exodus}, loaded once the game runs (server side). */
final class Roads {

    /** One being's spawn rule, resolved: the spawner entry and which biomes it's for. */
    private record Way(EntityType<?> type, MobCategory category, int weight, Object spawner,
                       Predicate<Holder<Biome>> where) {
    }

    private static volatile List<Way> ways;
    private static final Map<EntityType<?>, MobCategory> OURS = new IdentityHashMap<>();

    private Roads() {
    }

    private static List<Way> ways() {
        List<Way> w = ways;
        if (w != null) {
            return w;
        }
        if (!Creation.built()) {
            return List.of(); // too early to know the types; ask again later
        }
        synchronized (Roads.class) {
            if (ways != null) {
                return ways;
            }
            List<Way> built = new ArrayList<>();
            for (Being<?> b : Creation.wanderers()) {
                if (!b.exists()) {
                    continue;
                }
                EntityType<?> type = b.get();
                MobCategory category = type.getCategory();
                if (category == MobCategory.MISC) {
                    Log.warn("MiracleToolChain: " + b + " spawns(...), but its category is MISC, which the game never"
                            + " spawns naturally. Give its builder a category (MONSTER, CREATURE...).");
                    continue;
                }
                OURS.put(type, category);
                for (Being.Spawn s : b.spawns) {
                    built.add(new Way(type, category, s.weight(), spawner(type, s.min(), s.max()), biomes(s.biomes())));
                }
            }
            ways = List.copyOf(built);
            if (!built.isEmpty()) {
                Log.info("MiracleToolChain: " + built.size() + " natural spawn rule(s) for " + OURS.size() + " kind(s) of mob.");
            }
            return ways;
        }
    }

    /** SpawnerData's constructor takes (type, min, max) until 26.2 and (type, IntProvider) from 26.3. */
    private static Object spawner(EntityType<?> type, int min, int max) {
        try {
            for (Constructor<?> c : MobSpawnSettings.SpawnerData.class.getConstructors()) {
                Class<?>[] p = c.getParameterTypes();
                if (p.length == 3 && p[1] == int.class && p[2] == int.class) {
                    return c.newInstance(type, min, max);
                }
                if (p.length == 2 && p[1] != int.class) {
                    return c.newInstance(type, UniformInt.of(min, max));
                }
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("can't make a SpawnerData in this version: " + e, e);
        }
        throw new IllegalStateException("SpawnerData has no constructor MiracleToolChain knows in this version");
    }

    private static Predicate<Holder<Biome>> biomes(List<String> names) {
        List<Predicate<Holder<Biome>>> any = new ArrayList<>();
        for (String n : names) {
            if (n.startsWith("#")) {
                TagKey<Biome> tag = TagKey.create(Registries.BIOME, Identifier.parse(n.substring(1)));
                any.add(h -> h.is(tag));
            } else {
                ResourceKey<Biome> key = ResourceKey.create(Registries.BIOME, Identifier.parse(n));
                any.add(h -> h.is(key));
            }
        }
        return h -> {
            for (Predicate<Holder<Biome>> p : any) {
                if (p.test(h)) {
                    return true;
                }
            }
            return false;
        };
    }

    /** The mobs that may spawn here, ours added; null when there's nothing to add. */
    @SuppressWarnings("unchecked")
    static Object mobsAt(Object vanilla, Object level, Object category, Object pos, Object biome) {
        List<Way> all = ways();
        if (all.isEmpty()) {
            return null;
        }
        Holder<Biome> here = null;
        WeightedList.Builder<Object> out = null;
        for (Way w : all) {
            if (w.category() != category) {
                continue;
            }
            if (here == null) {
                here = biome != null ? (Holder<Biome>) biome : ((ServerLevel) level).getBiome((BlockPos) pos);
            }
            if (!w.where().test(here)) {
                continue;
            }
            if (out == null) {
                out = WeightedList.builder();
                for (Weighted<Object> v : ((WeightedList<Object>) vanilla).unwrap()) {
                    out.add(v.value(), v.weight());
                }
            }
            out.add(w.spawner(), w.weight());
        }
        return out == null ? null : out.build();
    }

    static Object placement(Object type) {
        MobCategory c = category(type);
        if (c == null) {
            return null;
        }
        return water(c) ? SpawnPlacementTypes.IN_WATER : SpawnPlacementTypes.ON_GROUND;
    }

    static Object heightmap(Object type) {
        return category(type) == null ? null : Heightmap.Types.MOTION_BLOCKING_NO_LEAVES;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static Boolean rules(Object type, Object level, Object reason, Object pos, Object random) {
        MobCategory c = category(type);
        if (c == null) {
            return null;
        }
        EntityType t = (EntityType) type;
        ServerLevelAccessor l = (ServerLevelAccessor) level;
        EntitySpawnReason r = (EntitySpawnReason) reason;
        BlockPos p = (BlockPos) pos;
        RandomSource rnd = (RandomSource) random;
        if (c == MobCategory.MONSTER) {
            return Monster.checkMonsterSpawnRules(t, l, r, p, rnd);
        }
        if (c == MobCategory.CREATURE) {
            return Animal.checkAnimalSpawnRules(t, l, r, p, rnd);
        }
        return Mob.checkMobSpawnRules(t, l, r, p, rnd);
    }

    private static MobCategory category(Object type) {
        if (ways().isEmpty()) {
            return null;
        }
        synchronized (Roads.class) {
            return OURS.get(type);
        }
    }

    private static boolean water(MobCategory c) {
        return c == MobCategory.WATER_CREATURE || c == MobCategory.WATER_AMBIENT
                || c == MobCategory.UNDERGROUND_WATER_CREATURE || c == MobCategory.AXOLOTLS;
    }
}
